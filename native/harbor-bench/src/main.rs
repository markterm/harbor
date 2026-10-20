//! M0 benchmark: how many times faster than real time a firmware runs on this device, per clock,
//! with the JIT on or off. Pushed to a phone and run over `adb shell` (tools/m0-bench.sh), or on
//! the build machine.
//!
//!   harbor-bench FIRMWARE.fwsc... [--ms 8000] [--mhz 240,200,160,120] [--no-jit] [--json]
//!
//! As fm1-live's --bench: three keys go down at 0.8-1 s so most firmwares make sound, the first
//! second is warm-up, and the figure is emulated time over wall time for the rest.

use std::time::Instant;

use fm1_soc::Action;
use harbor_engine::machine::Machine;

fn main() {
    let mut files = Vec::new();
    let (mut ms, mut clocks, mut jit, mut json) = (8000u64, vec![240u32, 200, 160, 120], true, false);
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        match a.as_str() {
            "--ms" => ms = args.next().and_then(|v| v.parse().ok()).expect("--ms N"),
            "--mhz" => clocks = args.next().expect("--mhz LIST").split(',').map(|c| c.parse().expect("MHz")).collect(),
            "--no-jit" => jit = false,
            "--json" => json = true,
            _ => files.push(a),
        }
    }
    if files.is_empty() {
        eprintln!("usage: harbor-bench FIRMWARE.fwsc... [--ms 8000] [--mhz 240,200,160,120] [--no-jit] [--json]");
        std::process::exit(2);
    }
    if !json {
        println!("{:<28} {:>5} {:>4} {:>9} {:>8}", "firmware", "MHz", "JIT", "x real", "frames");
    }
    for path in &files {
        let pkg = match std::fs::read(path) {
            Ok(p) => p,
            Err(e) => { eprintln!("{path}: {e}"); continue; }
        };
        let name = std::path::Path::new(path).file_stem().map_or(path.clone(), |s| s.to_string_lossy().into_owned());
        for &mhz in &clocks {
            match bench(&pkg, mhz, jit, ms) {
                Ok((x, frames, jit_active)) => {
                    if json {
                        println!("{{\"firmware\":\"{name}\",\"mhz\":{mhz},\"jit\":{jit_active},\"realtime\":{x:.3},\"audioFrames\":{frames}}}");
                    } else {
                        println!("{name:<28} {mhz:>5} {:>4} {x:>8.2}x {frames:>8}", if jit_active { "on" } else { "off" });
                    }
                }
                Err(e) => eprintln!("{name} at {mhz} MHz: {e}"),
            }
        }
    }
}

/// (steady times real time, audio frames made, whether the JIT was on)
fn bench(pkg: &[u8], mhz: u32, jit: bool, ms: u64) -> Result<(f64, usize, bool), String> {
    let mut m = Machine::boot(pkg, &[], mhz, None, jit)?;
    for (at, id) in [(800, 21), (900, 25), (1000, 28)] {
        let c = m.soc.periph.cycle_of_ms(at);
        m.soc.periph.script.push_back((c, Action::Key { id, down: true }));
    }
    let warmup = 1000.min(ms.saturating_sub(1));
    let mut steady = None;
    let mut frames = 0usize;
    while m.soc.periph.ms() < ms {
        if steady.is_none() && m.soc.periph.ms() >= warmup {
            steady = Some((Instant::now(), m.soc.periph.ms()));
        }
        m.run(100_000);
        if m.halted {
            return Err(format!("the CPU halted at {} ms", m.soc.periph.ms()));
        }
        frames += m.soc.periph.audio.len() / 2;
        m.soc.periph.audio.clear();
    }
    let (t, start) = steady.ok_or("too short to measure")?;
    let x = (m.soc.periph.ms() - start) as f64 / 1000.0 / t.elapsed().as_secs_f64();
    Ok((x, frames, m.jit()))
}
