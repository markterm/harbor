//! The audio output: an AAudio stream whose callback copies from the engine's ring.
//!
//! The stream opens at the device's own rate (an exclusive MMAP stream only runs there) and the
//! engine's 44.1 kHz frames are resampled in the callback. A disconnected device (headphones
//! unplugged, a USB interface removed) closes the stream; the app reopens it after its routing
//! check, through `restartAudio`.
//!
//! Some devices open an exclusive (MMAP) stream and then kill it at once, every time. A stream
//! that dies within a few seconds of opening, or won't start, makes the next open ask for less:
//! shared, then shared without the low-latency path. The step lasts for the process, since a
//! reopen would only fail the same way.

use std::sync::{Arc, Mutex};

use harbor_engine::engine::AudioOut;

#[derive(Clone, Copy, Default)]
pub struct Info {
    pub sample_rate: i32,
    pub frames_per_burst: i32,
    pub buffer_frames: i32,
    pub running: bool,
    pub exclusive: bool,
    pub xruns: i32,
    pub device_id: i32,
}

#[cfg(target_os = "android")]
mod imp {
    use super::*;
    use ndk::audio::{
        AudioCallbackResult, AudioDirection, AudioError, AudioFormat, AudioPerformanceMode, AudioSharingMode,
        AudioStream, AudioStreamBuilder, AudioStreamState, AudioUsage,
    };
    use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
    use std::time::{Duration, Instant};

    /// What the next open asks for: 0 exclusive, 1 shared, 2 shared without low latency.
    static LEVEL: AtomicU8 = AtomicU8::new(0);
    const LEVELS: u8 = 3;
    /// A stream that dies sooner than this after opening is the device refusing the mode.
    const EARLY: Duration = Duration::from_secs(3);

    /// An AAudio stream handle. AAudio's control calls may come from any thread as long as
    /// they don't race a close; every use goes through the host's mutex, which serialises them.
    struct Stream(AudioStream);
    // SAFETY: see above; the data callback never touches this handle.
    unsafe impl Send for Stream {}
    impl std::ops::Deref for Stream {
        type Target = AudioStream;
        fn deref(&self) -> &AudioStream { &self.0 }
    }

    pub struct Output {
        out: Arc<Mutex<AudioOut>>,
        low_latency: bool,
        stream: Option<Stream>,
        /// set by the error callback: the stream is gone
        dead: Arc<AtomicBool>,
        /// when the current stream opened, and at which level
        opened: Instant,
        level: u8,
    }

    impl Output {
        pub fn new(out: Arc<Mutex<AudioOut>>, low_latency: bool) -> Self {
            Output { out, low_latency, stream: None, dead: Arc::new(AtomicBool::new(false)), opened: Instant::now(), level: 0 }
        }

        /// The stream failed soon after opening: ask for less next time.
        fn step_down(&self) {
            if self.opened.elapsed() < EARLY && self.level + 1 < LEVELS
                && LEVEL.compare_exchange(self.level, self.level + 1, Ordering::AcqRel, Ordering::Acquire).is_ok()
            {
                log::warn!("audio stream died {} ms after opening: level {} from now on", self.opened.elapsed().as_millis(), self.level + 1);
            }
        }

        fn open(&mut self) -> Result<Stream, AudioError> {
            let out = self.out.clone();
            let dead = Arc::new(AtomicBool::new(false));
            self.dead = dead.clone();
            let mut rate = 0u32;
            let build = |level: u8| -> Result<AudioStream, AudioError> {
                let (sharing, perf) = match level {
                    0 => (AudioSharingMode::Exclusive, AudioPerformanceMode::LowLatency),
                    1 => (AudioSharingMode::Shared, AudioPerformanceMode::LowLatency),
                    _ => (AudioSharingMode::Shared, AudioPerformanceMode::None),
                };
                let out = out.clone();
                let dead = dead.clone();
                let mut rate_seen = 0u32;
                AudioStreamBuilder::new()?
                    .direction(AudioDirection::Output)
                    .format(AudioFormat::PCM_Float)
                    .channel_count(2)
                    .performance_mode(perf)
                    .sharing_mode(sharing)
                    .usage(AudioUsage::Game)
                    .data_callback(Box::new(move |stream: &AudioStream, data, frames| {
                        if rate_seen == 0 {
                            rate_seen = stream.sample_rate() as u32;
                        }
                        // SAFETY: AAudio hands a buffer of `frames` stereo float frames.
                        let buf = unsafe { std::slice::from_raw_parts_mut(data as *mut f32, frames as usize * 2) };
                        match out.try_lock() {
                            Ok(mut o) => o.render(buf, rate_seen, crate::now_ns()),
                            Err(_) => buf.fill(0.0),
                        }
                        AudioCallbackResult::Continue
                    }))
                    .error_callback(Box::new(move |_stream, err| {
                        log::warn!("audio stream error: {err:?}");
                        dead.store(true, Ordering::Release);
                    }))
                    .open_stream()
            };
            // from the current level down, until a device takes one
            let mut level = LEVEL.load(Ordering::Acquire);
            let stream = loop {
                match build(level) {
                    Ok(s) => break s,
                    Err(e) if level + 1 < LEVELS => {
                        log::info!("audio refused at level {level} ({e:?}); trying level {}", level + 1);
                        level += 1;
                    }
                    Err(e) => return Err(e),
                }
            };
            LEVEL.fetch_max(level, Ordering::AcqRel);
            self.level = level;
            self.opened = Instant::now();
            rate = rate.max(stream.sample_rate() as u32);
            let burst = stream.frames_per_burst().max(1);
            // one burst more than the minimum for "Low latency", four for "Stable"
            let _ = stream.set_buffer_size_in_frames(burst * if self.low_latency { 2 } else { 4 });
            log::info!("audio: {} Hz, burst {}, buffer {}, {:?}, level {level}", rate, burst, stream.buffer_size_in_frames(), stream.sharing_mode());
            Ok(Stream(stream))
        }

        pub fn ensure_running(&mut self) {
            // (a disconnected stream doesn't always call the error callback)
            if self.stream.as_ref().is_some_and(|s| s.state() == AudioStreamState::Disconnected) {
                self.dead.store(true, Ordering::Release);
            }
            if self.stream.is_some() && self.dead.load(Ordering::Acquire) {
                self.step_down();
                self.stream = None;
            }
            if self.stream.is_none() {
                match self.open() {
                    Ok(s) => self.stream = Some(s),
                    Err(e) => {
                        log::error!("could not open audio output: {e:?}");
                        return;
                    }
                }
            }
            if let Some(s) = &self.stream {
                if !matches!(s.state(), AudioStreamState::Started | AudioStreamState::Starting) {
                    if let Err(e) = s.request_start() {
                        // reopened (a level down, if it just opened) on the next try
                        log::error!("could not start audio output: {e:?}");
                        self.dead.store(true, Ordering::Release);
                    }
                }
            }
        }

        pub fn pause(&mut self) {
            if let Some(s) = &self.stream {
                let _ = s.request_pause();
            }
        }

        pub fn is_running(&self) -> bool {
            !self.dead.load(Ordering::Acquire)
                && self.stream.as_ref().is_some_and(|s| matches!(s.state(), AudioStreamState::Started | AudioStreamState::Starting))
        }

        pub fn info(&self) -> Info {
            let Some(s) = &self.stream else { return Info::default() };
            Info {
                sample_rate: s.sample_rate(),
                frames_per_burst: s.frames_per_burst(),
                buffer_frames: s.buffer_size_in_frames(),
                running: self.is_running(),
                exclusive: s.sharing_mode() == AudioSharingMode::Exclusive,
                xruns: s.x_run_count(),
                device_id: s.device_id(),
            }
        }
    }

    impl Drop for Output {
        fn drop(&mut self) {
            if let Some(s) = &self.stream {
                let _ = s.request_stop();
            }
        }
    }
}

/// Elsewhere (host builds, for `cargo check`/tests): no output.
#[cfg(not(target_os = "android"))]
mod imp {
    use super::*;

    pub struct Output;

    impl Output {
        pub fn new(_out: Arc<Mutex<AudioOut>>, _low_latency: bool) -> Self { Output }
        pub fn ensure_running(&mut self) {}
        pub fn pause(&mut self) {}
        pub fn is_running(&self) -> bool { false }
        pub fn info(&self) -> Info { Info::default() }
    }
}

pub use imp::Output;
