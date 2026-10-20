//! libharbor.so: harbor-engine for the app, through `com.markwhite.harbor.engine.NativeEngine`.
//!
//! One engine per process (the app's PlaybackService owns its lifetime), an AAudio output stream
//! feeding from the engine's ring, MIDI parsers per source, and the `.fm1save` codec. Calls are
//! coarse; nothing here is called per sample from Kotlin.

use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

use jni::objects::{JByteArray, JByteBuffer, JClass, JDoubleArray, JFloatArray, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jlongArray, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use harbor_engine::engine::{AudioOut, Boot, Cmd, Engine, RunState, WorkReport, LCD_PIXELS};
use harbor_engine::midi::{panic_messages, MidiParser};
use harbor_engine::recorder::Recorder;
use harbor_engine::savefile;
use harbor_engine::Board;

mod audio;
mod hint;

struct Host {
    engine: Engine,
    out: Arc<Mutex<AudioOut>>,
    audio: audio::Output,
    parsers: HashMap<i32, MidiParser>,
    recorder: Option<Recorder>,
}

static HOST: Mutex<Option<Host>> = Mutex::new(None);

fn host() -> MutexGuard<'static, Option<Host>> {
    HOST.lock().unwrap_or_else(|e| e.into_inner())
}

/// CLOCK_MONOTONIC in nanoseconds, the clock of `System.nanoTime` and of MIDI timestamps.
pub(crate) fn now_ns() -> u64 {
    #[cfg(unix)]
    {
        let mut ts = libc_timespec::new();
        // SAFETY: a valid out pointer.
        unsafe { libc_timespec::clock_gettime(&mut ts) };
        ts.0.tv_sec as u64 * 1_000_000_000 + ts.0.tv_nsec as u64
    }
    #[cfg(not(unix))]
    {
        use std::sync::OnceLock;
        static T0: OnceLock<std::time::Instant> = OnceLock::new();
        T0.get_or_init(std::time::Instant::now).elapsed().as_nanos() as u64 + 1
    }
}

#[cfg(unix)]
mod libc_timespec {
    pub struct Ts(pub libc::timespec);
    pub fn new() -> Ts { Ts(libc::timespec { tv_sec: 0, tv_nsec: 0 }) }
    pub unsafe fn clock_gettime(ts: &mut Ts) { libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts.0); }
}

/// On the engine thread as it starts: audio priority, and a performance hint session if the
/// system has them.
fn engine_thread_setup() -> Option<WorkReport> {
    audio_priority();
    let mut hint = hint::Hint::for_this_thread()?;
    Some(Box::new(move |busy, audio| hint.report(busy, audio)))
}

/// Raise the engine thread to audio priority (what Process.setThreadPriority(URGENT_AUDIO) does).
fn audio_priority() {
    #[cfg(target_os = "android")]
    // SAFETY: plain system calls on the current thread.
    unsafe {
        let tid = libc::gettid();
        if libc::setpriority(libc::PRIO_PROCESS, tid as libc::id_t, -19) != 0 {
            log::warn!("engine thread: could not raise priority");
        }
    }
}

fn throw(env: &mut JNIEnv, msg: &str) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", msg);
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Vec<u8> {
    if a.is_null() { Vec::new() } else { env.convert_byte_array(a).unwrap_or_default() }
}

fn new_bytes(env: &mut JNIEnv, b: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(_vm: jni::JavaVM, _: *mut std::ffi::c_void) -> jint {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_tag("harbor").with_max_level(log::LevelFilter::Info));
    jni::sys::JNI_VERSION_1_6
}

/// Start the engine and the audio output (idempotent). `lead`: frames the engine runs ahead.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_start(
    _env: JNIEnv, _: JClass, lead: jint, low_latency: jboolean,
) -> jboolean {
    let mut h = host();
    if h.is_none() {
        let (engine, out) = Engine::start(lead.max(64) as usize, engine_thread_setup);
        let out = Arc::new(Mutex::new(out));
        let audio = audio::Output::new(out.clone(), low_latency != 0);
        *h = Some(Host { engine, out, audio, parsers: HashMap::new(), recorder: None });
    }
    let host = h.as_mut().unwrap();
    host.audio.ensure_running();
    (host.audio.is_running() as u8) as jboolean
}

/// Stop the audio output and the engine.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_shutdown(_env: JNIEnv, _: JClass) {
    let h = host().take();
    drop(h);
}

/// Pause or resume the output (headphones unplugged, audio focus lost). The emulation pauses
/// with it: the engine only runs to keep its ring `lead` frames ahead of the output.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_setPaused(_env: JNIEnv, _: JClass, paused: jboolean) {
    if let Some(h) = host().as_mut() {
        if paused != 0 {
            h.audio.pause()
        } else {
            h.audio.ensure_running();
            h.engine.send(Cmd::Settle);
        }
    }
}

/// Reopen the output with new settings (buffer mode) or after the device changed.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_restartAudio(
    _env: JNIEnv, _: JClass, lead: jint, low_latency: jboolean,
) {
    if let Some(h) = host().as_mut() {
        h.engine.set_lead(lead.max(64) as usize);
        h.audio = audio::Output::new(h.out.clone(), low_latency != 0);
        h.audio.ensure_running();
        h.engine.send(Cmd::Settle);
    }
}

/// Boot a package. `sectors`: Harbor's sector store (savefile::sectors_to_bytes), or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_boot(
    mut env: JNIEnv, _: JClass, pkg: JByteArray, sectors: JByteArray, mhz: jint, auto_clock: jboolean,
    syx: JByteArray, jit: jboolean, id: jlong,
) {
    let pkg = bytes(&env, &pkg);
    let sectors = match savefile::sectors_from_bytes(&bytes(&env, &sectors)) {
        Ok(s) => s,
        Err(e) => return throw(&mut env, &e),
    };
    let syx = (!syx.is_null()).then(|| bytes(&env, &syx));
    if let Some(h) = host().as_mut() {
        h.engine.send(Cmd::Boot(Boot {
            pkg: Arc::new(pkg), sectors, mhz: mhz as u32, auto_clock: auto_clock != 0, syx, jit: jit != 0, id: id as u64,
        }));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_unload(_env: JNIEnv, _: JClass) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Unload) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_key(_env: JNIEnv, _: JClass, id: jint, down: jboolean) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Key { id: id as u8, down: down != 0 }) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_enc(_env: JNIEnv, _: JClass, enc: jint, clicks: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Enc { enc: enc as u8, clicks }) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_pot(_env: JNIEnv, _: JClass, value: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Pot(value.clamp(0, 1023) as u16)) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_analog(_env: JNIEnv, _: JClass, idx: jint, value: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Analog { idx: idx as u8, value: value.clamp(0, 1023) as u16 }) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_velocity(_env: JNIEnv, _: JClass, us: jint) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Vel(us.max(0) as u32)) }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_setClock(_env: JNIEnv, _: JClass, mhz: jint, auto: jboolean) {
    if let Some(h) = host().as_ref() { h.engine.send(Cmd::Clock { mhz: mhz as u32, auto: auto != 0 }) }
}

// ------------------------------------------------------------------------------------- MIDI

/// A MIDI source was opened. `remap`: channel 0..15 to move channel messages to, or -1.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiOpen(_env: JNIEnv, _: JClass, source: jint, remap: jint) {
    if let Some(h) = host().as_mut() {
        h.parsers.insert(source, MidiParser::new((0..16).contains(&remap).then_some(remap as u8)));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiRemap(_env: JNIEnv, _: JClass, source: jint, remap: jint) {
    if let Some(h) = host().as_mut() {
        let p = h.parsers.entry(source).or_default();
        p.remap = (0..16).contains(&remap).then_some(remap as u8);
    }
}

/// Bytes from a source (`MidiReceiver.onSend`), stamped with its CLOCK_MONOTONIC timestamp.
/// Returns true when a message other than real-time went out (the activity dot).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiData(
    env: JNIEnv, _: JClass, source: jint, data: JByteArray, offset: jint, count: jint, timestamp: jlong,
) -> jboolean {
    let all = bytes(&env, &data);
    let (o, n) = (offset.max(0) as usize, count.max(0) as usize);
    let Some(chunk) = all.get(o..o.saturating_add(n).min(all.len())) else { return JNI_FALSE };
    let now = now_ns();
    let mut h = host();
    let Some(h) = h.as_mut() else { return JNI_FALSE };
    let Host { engine, parsers, .. } = h;
    let parser = parsers.entry(source).or_default();
    let active = parser.feed(chunk, &mut |m| engine.midi(m, timestamp.max(0) as u64, now));
    if active { JNI_TRUE } else { JNI_FALSE }
}

/// A source went away: release the notes it held.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_midiClose(_env: JNIEnv, _: JClass, source: jint) {
    if let Some(h) = host().as_mut() {
        if let Some(mut p) = h.parsers.remove(&source) {
            let now = now_ns();
            let engine = &h.engine;
            p.release_all(&mut |m| engine.midi(m, 0, now));
        }
    }
}

/// All notes off on every channel, and every source's held notes released.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_panic(_env: JNIEnv, _: JClass) {
    if let Some(h) = host().as_mut() {
        let now = now_ns();
        let Host { engine, parsers, .. } = h;
        for p in parsers.values_mut() {
            p.release_all(&mut |m| engine.midi(m, 0, now));
        }
        for m in panic_messages() {
            engine.midi(&m, 0, now);
        }
    }
}

// ----------------------------------------------------------------------------------- the view

/// Copy the screen (240x240 RGB565, little-endian) into `buf` (a direct buffer of 115200 bytes)
/// if its revision isn't `since` (the caller's last copy). Returns the revision copied (or `since`).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_lcd(env: JNIEnv, _: JClass, buf: JByteBuffer, since: jlong) -> jlong {
    let h = host();
    let Some(h) = h.as_ref() else { return since };
    let v = h.engine.shared.view.lock().unwrap();
    if v.lcd_rev as jlong != since {
        if let (Ok(ptr), Ok(cap)) = (env.get_direct_buffer_address(&buf), env.get_direct_buffer_capacity(&buf)) {
            if cap >= LCD_PIXELS * 2 {
                // SAFETY: a direct buffer of at least that many bytes, owned by the caller.
                let dst = unsafe { std::slice::from_raw_parts_mut(ptr, LCD_PIXELS * 2) };
                for (d, p) in dst.chunks_exact_mut(2).zip(v.lcd.iter()) {
                    d.copy_from_slice(&p.to_le_bytes());
                }
                return v.lcd_rev as jlong;
            }
        }
    }
    since
}

/// The LEDs' on-fractions over the last ~16 ms (41 floats; SMK-37: its button LEDs first).
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_leds(env: JNIEnv, _: JClass, out: JFloatArray) {
    if let Some(h) = host().as_ref() {
        let leds = h.engine.shared.view.lock().unwrap().leds;
        let _ = env.set_float_array_region(&out, 0, &leds);
    }
}

/// Status fields, in the order of NativeEngine.Status (Kotlin): see there.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_status(env: JNIEnv, _: JClass, out: JDoubleArray) {
    let h = host();
    let Some(h) = h.as_ref() else { return };
    let v = h.engine.shared.view.lock().unwrap();
    let a = h.audio.info();
    let state = match v.state { RunState::Idle => 0.0, RunState::Running => 1.0, RunState::Halted => 2.0, RunState::Failed => 3.0 };
    let fields = [
        state,
        v.boot_id as f64,
        (v.board == Board::Smk37) as u8 as f64,
        v.mhz as f64,
        v.auto_clock as u8 as f64,
        v.clock_steps as f64,
        v.jit as u8 as f64,
        v.speed as f64,
        v.late as f64,
        v.starved as f64,
        v.peak[0] as f64,
        v.peak[1] as f64,
        v.clip as u8 as f64,
        v.flash.revision as f64,
        v.flash.boot_id as f64,
        v.midi_dropped as f64,
        v.lead as f64,
        a.sample_rate as f64,
        a.frames_per_burst as f64,
        a.buffer_frames as f64,
        a.running as u8 as f64,
        a.exclusive as u8 as f64,
        a.xruns as f64,
        a.device_id as f64,
        v.midi_in as f64,
        h.recorder.as_ref().map_or(-1.0, |r| r.frames() as f64),
    ];
    let _ = env.set_double_array_region(&out, 0, &fields);
}

/// The last boot error, or "".
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_error(env: JNIEnv, _: JClass) -> jstring {
    let msg = host().as_ref().map(|h| h.engine.shared.view.lock().unwrap().error.clone()).unwrap_or_default();
    env.new_string(msg).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The flash sectors last published (sector store format), for the boot `bootId`, or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_flashSectors(mut env: JNIEnv, _: JClass) -> jbyteArray {
    let data = host().as_ref().map(|h| savefile::sectors_to_bytes(&h.engine.shared.view.lock().unwrap().flash.sectors));
    match data { Some(d) => new_bytes(&mut env, &d), None => std::ptr::null_mut() }
}

/// Wait (at most `timeoutMs`) for the firmware to finish writing flash, then return its sectors.
/// Null if it is still writing or nothing is running. Blocks: call off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_flush(mut env: JNIEnv, _: JClass, timeout_ms: jint) -> jbyteArray {
    // (no lock held while waiting: the engine thread answers)
    let request = {
        let h = host();
        h.as_ref().map(|h| {
            let (tx, rx) = std::sync::mpsc::channel();
            h.engine.send(Cmd::Flush(tx));
            rx
        })
    };
    let flash = request.and_then(|rx| rx.recv_timeout(Duration::from_millis(timeout_ms.max(0) as u64)).ok().flatten());
    match flash { Some(f) => new_bytes(&mut env, &savefile::sectors_to_bytes(&f.sectors)), None => std::ptr::null_mut() }
}

// -------------------------------------------------------------------------------- recording

/// Start recording the firmware's output as a WAV into `fd` (a writable, seekable file; this
/// takes ownership and closes it). False if a recording is already running or the file failed.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_recordStart(_env: JNIEnv, _: JClass, fd: jint) -> jboolean {
    #[cfg(unix)]
    {
        use std::os::fd::FromRawFd;
        // SAFETY: the caller hands over an open descriptor it no longer uses (detachFd).
        let file = unsafe { std::fs::File::from_raw_fd(fd) };
        let mut h = host();
        let Some(h) = h.as_mut().filter(|h| h.recorder.is_none()) else { return JNI_FALSE };
        match Recorder::start(file) {
            Ok((rec, tap)) => {
                h.engine.send(Cmd::Record(Some(tap)));
                h.recorder = Some(rec);
                JNI_TRUE
            }
            Err(e) => {
                log::error!("could not start a recording: {e}");
                JNI_FALSE
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = fd;
        JNI_FALSE
    }
}

/// End the recording: [frames, dropped frames], or null if none was running or the file failed.
/// Blocks until the file is complete: call off the main thread.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_recordStop(env: JNIEnv, _: JClass) -> jlongArray {
    // (the writer finishes once the engine thread drops its tap: not under the host's lock)
    let rec = {
        let mut h = host();
        h.as_mut().and_then(|h| {
            h.engine.send(Cmd::Record(None));
            h.recorder.take()
        })
    };
    let Some(rec) = rec else { return std::ptr::null_mut() };
    match rec.finish() {
        Ok(s) => {
            let Ok(arr) = env.new_long_array(2) else { return std::ptr::null_mut() };
            let _ = env.set_long_array_region(&arr, 0, &[s.frames as jlong, s.dropped as jlong]);
            arr.into_raw()
        }
        Err(e) => {
            log::error!("the recording failed: {e}");
            std::ptr::null_mut()
        }
    }
}

// ------------------------------------------------------------------------- packages and saves

/// Check a package unpacks. Returns "fm1" or "smk37"; throws with the reason otherwise.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_packageBoard(mut env: JNIEnv, _: JClass, pkg: JByteArray) -> jstring {
    let pkg = bytes(&env, &pkg);
    match std::panic::catch_unwind(|| harbor_engine::machine::package_board(&pkg)).unwrap_or_else(|_| Err("malformed firmware package".into())) {
        Ok(b) => env.new_string(if b == Board::Smk37 { "smk37" } else { "fm1" }).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(e) => { throw(&mut env, &e); std::ptr::null_mut() }
    }
}

/// Encode a `.fm1save`. Throws on invalid data.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_saveEncode(
    mut env: JNIEnv, _: JClass, name: JString, pkg: JByteArray, sectors: JByteArray, include_package: jboolean,
) -> jbyteArray {
    let name: String = env.get_string(&name).map(Into::into).unwrap_or_default();
    let pkg = bytes(&env, &pkg);
    let result = savefile::sectors_from_bytes(&bytes(&env, &sectors))
        .and_then(|sectors| savefile::encode(&savefile::SaveData { name, pkg, sectors }, include_package != 0));
    match result {
        Ok(b) => new_bytes(&mut env, &b),
        Err(e) => { throw(&mut env, &e); std::ptr::null_mut() }
    }
}

/// Decode a `.fm1save` into [name: String, package: byte[], sectors: byte[]]. `fallback`: the
/// package to use when the save carries none. Throws with the reason on invalid files.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_saveDecode(
    mut env: JNIEnv, _: JClass, data: JByteArray, fallback: JByteArray,
) -> jobjectArray {
    let data = bytes(&env, &data);
    let fallback = (!fallback.is_null()).then(|| bytes(&env, &fallback));
    let save = match savefile::decode(&data, fallback.as_deref()) {
        Ok(s) => s,
        Err(e) => { throw(&mut env, &e); return std::ptr::null_mut(); }
    };
    let build = |env: &mut JNIEnv| -> jni::errors::Result<jobjectArray> {
        let arr = env.new_object_array(3, "java/lang/Object", JObject::null())?;
        let name = env.new_string(&save.name)?;
        let pkg = env.byte_array_from_slice(&save.pkg)?;
        let sectors = env.byte_array_from_slice(&savefile::sectors_to_bytes(&save.sectors))?;
        env.set_object_array_element(&arr, 0, name)?;
        env.set_object_array_element(&arr, 1, pkg)?;
        env.set_object_array_element(&arr, 2, sectors)?;
        Ok(arr.into_raw())
    };
    build(&mut env).unwrap_or(std::ptr::null_mut())
}

/// The package hash a save names (to find a bring-your-own package), or null.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_savePeekHash(env: JNIEnv, _: JClass, data: JByteArray) -> jstring {
    match savefile::peek_hash(&bytes(&env, &data)) {
        Some(h) => env.new_string(h).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// Whether this build compiles hot firmware code (AArch64) or interprets it.
#[no_mangle]
pub extern "system" fn Java_com_markwhite_harbor_engine_NativeEngine_jitAvailable(_env: JNIEnv, _: JClass) -> jboolean {
    harbor_engine::machine::jit_usable() as jboolean
}
