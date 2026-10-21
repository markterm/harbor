//! The engine with a real firmware. Packages can't live in this repository (each is its author's),
//! so these run only when HARBOR_TEST_FWSC names one, e.g. Felucca from the FM-1 Firmware Hub:
//!
//!   curl -LO https://thefirmwarehub.com/fw/felucca-1.6.1.fwsc
//!   HARBOR_TEST_FWSC=felucca-1.6.1.fwsc cargo test --release -p harbor-engine --test firmware
//!
//! A thread stands in for the audio device: it takes 10 ms of frames every 10 ms.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use harbor_engine::engine::{AudioOut, Boot, Cmd, Engine, RunState, RATE};

/// How long a boot may take: generous, since on a host without the JIT the tests' machines
/// share the CPU interpreted.
const BOOT_WAIT: Duration = Duration::from_secs(30);

fn package() -> Option<Vec<u8>> {
    let path = std::env::var("HARBOR_TEST_FWSC").ok()?;
    Some(std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}")))
}

/// A fake output device; returns the peak of what it played.
fn device(mut out: AudioOut, stop: Arc<AtomicBool>) -> std::thread::JoinHandle<f32> {
    std::thread::spawn(move || {
        let t0 = Instant::now();
        let mut buf = vec![0f32; 2 * (RATE as usize / 100)];
        let mut peak = 0f32;
        let mut n = 0u32;
        while !stop.load(Ordering::Relaxed) {
            out.render(&mut buf, RATE, t0.elapsed().as_nanos() as u64 + 1);
            peak = buf.iter().fold(peak, |p, s| p.max(s.abs()));
            n += 1;
            let next = t0 + Duration::from_millis(10 * n as u64);
            std::thread::sleep(next.saturating_duration_since(Instant::now()));
        }
        peak
    })
}

fn boot(engine: &Engine, pkg: Vec<u8>, auto_clock: bool) {
    engine.send(Cmd::Boot(Boot { pkg: Arc::new(pkg), sectors: vec![], mhz: 240, auto_clock, syx: None, jit: true, id: 1 }));
}

/// Running, with two screens drawn: Felucca's splash, then its own (110 and 595 ms after boot).
/// A silent firmware's screen then holds still, and the view samples it every 16 ms, so asking for
/// more than two changes passed only when a sample caught a frame half drawn.
fn drawn(v: &harbor_engine::engine::View) -> bool {
    v.state == RunState::Running && v.lcd_rev >= 2
}

fn wait_for(engine: &Engine, timeout: Duration, mut f: impl FnMut(&harbor_engine::engine::View) -> bool) -> bool {
    let t = Instant::now();
    while t.elapsed() < timeout {
        if f(&engine.shared.view.lock().unwrap()) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    // what it was doing instead, so a timeout can be told from a halt or a stall
    let v = engine.shared.view.lock().unwrap();
    eprintln!(
        "timed out: state {:?}, lcd_rev {}, speed {:.2}, jit {}, late {}, starved {}, error {:?}",
        v.state, v.lcd_rev, v.speed, v.jit, v.late, v.starved, v.error
    );
    false
}

#[test]
fn boots_draws_and_plays_a_key() {
    let Some(pkg) = package() else { return };
    let (engine, out) = Engine::start(2048, || None);
    let stop = Arc::new(AtomicBool::new(false));
    let dev = device(out, stop.clone());
    boot(&engine, pkg, false);
    assert!(wait_for(&engine, BOOT_WAIT, drawn), "booted and drew");
    std::thread::sleep(Duration::from_millis(1500));
    engine.send(Cmd::Key { id: 21, down: true }); // C4
    std::thread::sleep(Duration::from_millis(800));
    engine.send(Cmd::Key { id: 21, down: false });
    std::thread::sleep(Duration::from_millis(200));
    stop.store(true, Ordering::Relaxed);
    let peak = dev.join().unwrap();
    assert!(peak > 0.001, "the key made sound (peak {peak})");
}

#[test]
fn midi_reaches_the_jack() {
    let Some(pkg) = package() else { return };
    let (engine, out) = Engine::start(1024, || None);
    let stop = Arc::new(AtomicBool::new(false));
    let dev = device(out, stop.clone());
    boot(&engine, pkg, false);
    assert!(wait_for(&engine, BOOT_WAIT, |v| v.state == RunState::Running));
    let t0 = Instant::now();
    let now = || t0.elapsed().as_nanos() as u64 + 1;
    engine.midi(&[0x90, 60, 100], now(), now());
    engine.midi(&[0x80, 60, 0], now(), now());
    assert!(wait_for(&engine, Duration::from_secs(3), |v| v.midi_in == 6), "6 bytes into the jack");
    stop.store(true, Ordering::Relaxed);
    dev.join().unwrap();
}

#[test]
fn the_automatic_clock_steps_down_on_a_slow_device_and_keeps_flash() {
    let Some(pkg) = package() else { return };
    let (engine, out) = Engine::start(2048, || None);
    let stop = Arc::new(AtomicBool::new(false));
    let dev = device(out, stop.clone());
    boot(&engine, pkg, true);
    assert!(wait_for(&engine, BOOT_WAIT, |v| v.state == RunState::Running));
    let flash_before = Arc::new(Mutex::new(engine.shared.view.lock().unwrap().flash.sectors.clone()));
    // three times slower than this machine: no clock keeps up for long, so it steps down
    engine.set_throttle(3.0);
    assert!(
        wait_for(&engine, Duration::from_secs(20), |v| v.clock_steps >= 1 && v.mhz < 240),
        "stepped down: {} MHz after {} steps", engine.shared.view.lock().unwrap().mhz, engine.shared.view.lock().unwrap().clock_steps
    );
    let v = engine.shared.view.lock().unwrap();
    assert_eq!(v.state, RunState::Running, "still running after the reboot");
    let before = flash_before.lock().unwrap();
    for s in before.iter() {
        assert!(v.flash.sectors.contains(s), "flash written before the step survives it");
    }
    drop(v);
    stop.store(true, Ordering::Relaxed);
    dev.join().unwrap();
}

#[test]
fn a_fixed_clock_never_steps() {
    let Some(pkg) = package() else { return };
    let (engine, out) = Engine::start(2048, || None);
    let stop = Arc::new(AtomicBool::new(false));
    let dev = device(out, stop.clone());
    boot(&engine, pkg, false);
    assert!(wait_for(&engine, BOOT_WAIT, |v| v.state == RunState::Running));
    engine.set_throttle(3.0);
    std::thread::sleep(Duration::from_secs(8));
    let v = engine.shared.view.lock().unwrap();
    assert_eq!((v.mhz, v.clock_steps), (240, 0));
    drop(v);
    stop.store(true, Ordering::Relaxed);
    dev.join().unwrap();
}

/// Felucca only: its ARP page's MODE (KNOB1) switched on, a held key arpeggiates, so the output
/// pulses at the arp's rate instead of holding one note.
#[test]
fn felucca_arpeggiates_a_held_key() {
    let Some(pkg) = package() else { return };
    if !std::env::var("HARBOR_TEST_FWSC").unwrap().to_lowercase().contains("felucca") {
        return;
    }
    use fm1_soc::periph::Action;
    let mut m = harbor_engine::machine::Machine::boot(&pkg, &[], 240, None, true).unwrap();
    let run_ms = |m: &mut harbor_engine::machine::Machine, ms: u32| -> Vec<i16> {
        let mut audio = Vec::new();
        for _ in 0..ms {
            m.run(240_000);
            audio.extend(m.soc.periph.audio.drain(..));
        }
        audio
    };
    // windowed loudness: how much it swings between 10 ms windows
    let swing = |a: &[i16]| -> (f32, f32) {
        let rms: Vec<f32> = a.chunks(882).map(|c| (c.iter().map(|&s| (s as f32).powi(2)).sum::<f32>() / c.len() as f32).sqrt()).collect();
        let max = rms.iter().cloned().fold(0f32, f32::max);
        let min = rms.iter().cloned().fold(f32::MAX, f32::min);
        (min, max)
    };
    run_ms(&mut m, 3000);
    let key = 14 + 12; // F4
    m.soc.periph.apply(&Action::Key { id: key, down: true });
    let held = run_ms(&mut m, 1500);
    m.soc.periph.apply(&Action::Key { id: key, down: false });
    run_ms(&mut m, 1000);
    // ARP: the page, then MODE one step on
    m.soc.periph.apply(&Action::Key { id: 10, down: true });
    run_ms(&mut m, 100);
    m.soc.periph.apply(&Action::Key { id: 10, down: false });
    run_ms(&mut m, 300);
    m.soc.periph.apply(&Action::Enc { enc: 2, clicks: 1 });
    run_ms(&mut m, 500);
    m.soc.periph.apply(&Action::Key { id: key, down: true });
    let arp = run_ms(&mut m, 3000);
    let (h0, h1) = swing(&held[44100..]);
    let (a0, a1) = swing(&arp[44100..]);
    eprintln!("held: rms {h0:.0}..{h1:.0}; arp: rms {a0:.0}..{a1:.0}");
    assert!(h1 > 100.0, "a held key sounds");
    assert!(a1 > 100.0 && a0 < a1 * 0.3, "the arp gates the note: rms {a0:.0}..{a1:.0}");
}

#[test]
fn records_what_the_firmware_plays() {
    let Some(pkg) = package() else { return };
    let (engine, out) = Engine::start(2048, || None);
    let stop = Arc::new(AtomicBool::new(false));
    let dev = device(out, stop.clone());
    boot(&engine, pkg, false);
    assert!(wait_for(&engine, BOOT_WAIT, drawn), "booted");
    std::thread::sleep(Duration::from_millis(1500));

    let path = std::env::temp_dir().join(format!("harbor-rec-{}.wav", std::process::id()));
    let file = std::fs::File::options().read(true).write(true).create(true).truncate(true).open(&path).unwrap();
    let (rec, tap) = harbor_engine::recorder::Recorder::start(file).unwrap();
    engine.send(Cmd::Record(Some(tap)));
    engine.send(Cmd::Key { id: 21, down: true });
    std::thread::sleep(Duration::from_millis(800));
    engine.send(Cmd::Key { id: 21, down: false });
    std::thread::sleep(Duration::from_millis(200));
    engine.send(Cmd::Record(None));
    let s = rec.finish().unwrap();
    stop.store(true, Ordering::Relaxed);
    dev.join().unwrap();

    let b = std::fs::read(&path).unwrap();
    std::fs::remove_file(&path).ok();
    // about a second of audio, in real time, all of it written
    assert!((RATE as u64 * 8 / 10..RATE as u64 * 15 / 10).contains(&s.frames), "{} frames", s.frames);
    assert_eq!(s.dropped, 0);
    assert_eq!(b.len() as u64, 44 + s.frames * 4);
    let peak = b[44..].chunks_exact(2).map(|c| i16::from_le_bytes([c[0], c[1]]).unsigned_abs()).max().unwrap();
    assert!(peak > 30, "the key is in the recording (peak {peak})");
}
