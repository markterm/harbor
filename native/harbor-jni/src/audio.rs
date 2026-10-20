//! The audio output: an AAudio stream whose callback copies from the engine's ring.
//!
//! The stream opens at the device's own rate (an exclusive MMAP stream only runs there) and the
//! engine's 44.1 kHz frames are resampled in the callback. A disconnected device (headphones
//! unplugged, a USB interface removed) closes the stream; the app reopens it after its routing
//! check, through `restartAudio`.

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
    use std::sync::atomic::{AtomicBool, Ordering};

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
    }

    impl Output {
        pub fn new(out: Arc<Mutex<AudioOut>>, low_latency: bool) -> Self {
            Output { out, low_latency, stream: None, dead: Arc::new(AtomicBool::new(false)) }
        }

        fn open(&mut self) -> Result<Stream, AudioError> {
            let out = self.out.clone();
            let dead = Arc::new(AtomicBool::new(false));
            self.dead = dead.clone();
            let mut rate = 0u32;
            let build = |sharing: AudioSharingMode| -> Result<AudioStream, AudioError> {
                let out = out.clone();
                let dead = dead.clone();
                let mut rate_seen = 0u32;
                AudioStreamBuilder::new()?
                    .direction(AudioDirection::Output)
                    .format(AudioFormat::PCM_Float)
                    .channel_count(2)
                    .performance_mode(AudioPerformanceMode::LowLatency)
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
            let stream = build(AudioSharingMode::Exclusive).or_else(|e| {
                log::info!("exclusive audio refused ({e:?}); shared");
                build(AudioSharingMode::Shared)
            })?;
            rate = rate.max(stream.sample_rate() as u32);
            let burst = stream.frames_per_burst().max(1);
            // one burst more than the minimum for "Low latency", four for "Stable"
            let _ = stream.set_buffer_size_in_frames(burst * if self.low_latency { 2 } else { 4 });
            log::info!("audio: {} Hz, burst {}, buffer {}, {:?}", rate, burst, stream.buffer_size_in_frames(), stream.sharing_mode());
            Ok(Stream(stream))
        }

        pub fn ensure_running(&mut self) {
            if self.dead.load(Ordering::Acquire) {
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
                        log::error!("could not start audio output: {e:?}");
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
