//! One booted FM-1 (or SMK-37): the CPU cores and the SoC, booted from a `.fwsc` package with
//! the flash sectors a firmware saved earlier written over it. After fm1-plugin's engine.rs.

use fm1_core::{Bus, Cpu};
use fm1_soc::{machine, Board, Soc};

use crate::savefile::{validate_sectors, Sector, SECTOR_SIZE};

/// Whether fm1-core compiles hot code on this host (AArch64 Linux, Android or macOS); elsewhere it
/// interprets.
pub const JIT_AVAILABLE: bool =
    cfg!(all(target_arch = "aarch64", any(target_os = "linux", target_os = "android", target_os = "macos")));

/// Whether this process may map memory read-write-execute, as fm1-core's JIT code cache does on
/// Linux and Android (SELinux's execmem can forbid it). When it may not, fm1-core falls back to
/// interpreting but retries the mapping block after block, so Harbor boots without the JIT
/// instead. Checked once.
pub fn jit_usable() -> bool {
    use std::sync::OnceLock;
    static OK: OnceLock<bool> = OnceLock::new();
    *OK.get_or_init(|| {
        if !JIT_AVAILABLE {
            return false;
        }
        #[cfg(any(target_os = "linux", target_os = "android"))]
        // SAFETY: an anonymous private mapping, unmapped right away.
        unsafe {
            let len = 1 << 16;
            let p = libc::mmap(std::ptr::null_mut(), len, libc::PROT_READ | libc::PROT_WRITE | libc::PROT_EXEC,
                libc::MAP_PRIVATE | libc::MAP_ANONYMOUS, -1, 0);
            if p == libc::MAP_FAILED {
                return false;
            }
            libc::munmap(p, len);
        }
        true
    })
}

const RAM_BASE: u32 = 0x01c0_0000;
const RAM_SIZE: usize = 1 << 20;

pub struct Machine {
    cpu: Cpu,
    cpu1: Option<Cpu>,
    pub soc: Soc,
    pending_tick: bool,
    pub halted: bool,
    /// the flash as booted (before saved sectors and a DX7 bank): what a save is compared with
    flash0: Vec<u8>,
}

impl Machine {
    /// Boot `pkg` at `mhz`, restore `sectors` over its flash, then import a DX7 `syx` bank
    /// (stock firmware only) so its voices count as changed sectors and get saved.
    pub fn boot(pkg: &[u8], sectors: &[Sector], mhz: u32, syx: Option<&[u8]>, jit: bool) -> Result<Self, String> {
        validate_sectors(sectors)?;
        let st = std::panic::catch_unwind(|| fm1_soc::stock_from_package(pkg, None))
            .map_err(|_| "malformed firmware package".to_owned())??;
        let mut soc = Soc::new(RAM_SIZE, st.xip);
        soc.periph.hz = mhz as u64 * 1_000_000;
        soc.periph.skip_idle = true;
        let mut cpu = Cpu::new(st.entry);
        cpu.jit_on = jit && jit_usable();
        cpu.user = true; // the SPL hands cpu0 over in user mode (fm1-core's Cpu::user)
        let params = RAM_BASE + 0x7fc00;
        soc.write32(params, params + 0x40);
        cpu.regs[0] = fm1_soc::stock_boot(&mut soc, &st.flash, params, st.board);
        let flash0 = soc.flash.mem.clone();
        restore_flash(&mut soc, sectors);
        if let Some(bank) = syx {
            let usr = st.usr.ok_or("this firmware has no DX7 voice bank")?;
            let mut flash = soc.flash.mem.clone();
            fm1_soc::fwsc::usr_load_syx(&mut flash, usr, bank, 0)?;
            let changed: Vec<Sector> = (0..flash.len() / SECTOR_SIZE)
                .map(|s| s * SECTOR_SIZE)
                .filter(|&at| flash[at..at + SECTOR_SIZE] != soc.flash.mem[at..at + SECTOR_SIZE])
                .map(|at| Sector { at: at as u32, data: flash[at..at + SECTOR_SIZE].to_vec() })
                .collect();
            restore_flash(&mut soc, &changed);
        }
        Ok(Machine { cpu, cpu1: None, soc, pending_tick: false, halted: false, flash0 })
    }

    /// fm1-live's loop: cpu0's blocks, cpu1 beside once the firmware releases it.
    pub fn run(&mut self, n: u32) {
        let jit = self.cpu.jit_on;
        let Machine { cpu, cpu1, soc, pending_tick, .. } = self;
        if machine::run_dual(cpu1, soc, n, RAM_SIZE, jit, |soc, k| machine::run_blocks(cpu, soc, k, pending_tick)).is_err() {
            self.halted = true;
        }
    }

    pub fn board(&self) -> Board { self.soc.board }
    pub fn mhz(&self) -> u32 { (self.soc.periph.hz / 1_000_000) as u32 }
    pub fn jit(&self) -> bool { self.cpu.jit_on }

    /// The flash sectors that differ from the booted package's.
    pub fn changed_sectors(&self) -> Vec<Sector> {
        let (now, then) = (&self.soc.flash.mem, &self.flash0);
        (0..now.len().min(then.len()) / SECTOR_SIZE)
            .map(|s| s * SECTOR_SIZE)
            .filter(|&at| now[at..at + SECTOR_SIZE] != then[at..at + SECTOR_SIZE])
            .map(|at| Sector { at: at as u32, data: now[at..at + SECTOR_SIZE].to_vec() })
            .collect()
    }
}

/// The board a package is for, or why it doesn't unpack.
pub fn package_board(pkg: &[u8]) -> Result<Board, String> {
    fm1_soc::stock_from_package(pkg, None).map(|st| st.board)
}

/// Write whole sectors into the flash chip and the XIP window over it, as a live SPI erase and
/// program would.
fn restore_flash(soc: &mut Soc, sectors: &[Sector]) {
    for s in sectors {
        let at = s.at as usize;
        soc.flash.mem[at..at + s.data.len()].copy_from_slice(&s.data);
        let from = at.max(fm1_soc::XIP_FLASH_OFFSET);
        let end = (at + s.data.len()).min(fm1_soc::XIP_FLASH_OFFSET + soc.xip.len());
        if from < end {
            soc.xip[from - fm1_soc::XIP_FLASH_OFFSET..end - fm1_soc::XIP_FLASH_OFFSET]
                .copy_from_slice(&s.data[from - at..end - at]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn machine() -> Machine {
        let soc = Soc::new(RAM_SIZE, vec![0x55; 0x80000]);
        let flash0 = soc.flash.mem.clone();
        Machine { cpu: Cpu::new(0x0200_0120), cpu1: None, soc, pending_tick: false, halted: false, flash0 }
    }

    #[test]
    fn restored_sectors_show_through_xip_and_count_as_changed() {
        let mut m = machine();
        let mut data = vec![0xff; SECTOR_SIZE];
        data[0] = 0x12;
        restore_flash(&mut m.soc, &[Sector { at: 0x74000, data }]);
        assert_eq!(m.soc.read32(0x0207_0000), 0xffff_ff12);
        assert_eq!(m.soc.read32(0x0207_0010), 0xffff_ffff);
        let changed = m.changed_sectors();
        assert_eq!(changed.len(), 1);
        assert_eq!(changed[0].at, 0x74000);
    }

    #[test]
    fn a_malformed_package_is_an_error_not_a_panic() {
        assert!(Machine::boot(&[0u8; 100], &[], 240, None, false).is_err());
        assert!(Machine::boot(&vec![0x42u8; 960], &[], 240, None, false).is_err());
    }
}
