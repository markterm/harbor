//! Recording: the firmware's output as it makes it (16-bit stereo at 44.1 kHz, before any
//! resampling for the device) into a WAV file.
//!
//! The engine thread only copies each chunk into a ring ([`Tap::push`]) and never waits on the
//! file; a writer thread of its own drains the ring to disk. The recording ends when the engine
//! lets go of its tap: the writer drains what is left, fills in the WAV header's sizes and
//! closes the file.

use std::fs::File;
use std::io::{self, BufWriter, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

use crate::engine::RATE;

/// Two seconds of frames: the writer may stall that long (a slow flash write) before frames drop.
const RING: usize = 2 * RATE as usize;
/// A WAV's sizes are 32-bit: past this the recording stops growing (about 6 h 45 min).
const MAX_FRAMES: u64 = (u32::MAX as u64 - 36) / 4;
const HEADER: u64 = 44;

/// The engine thread's end: frames in, never blocking.
pub struct Tap {
    ring: rtrb::Producer<[i16; 2]>,
    dropped: Arc<AtomicU64>,
}

impl Tap {
    /// Interleaved stereo samples, as the firmware made them.
    pub fn push(&mut self, samples: &[i16]) {
        let mut lost = 0;
        for f in samples.chunks_exact(2) {
            if self.ring.push([f[0], f[1]]).is_err() {
                lost += 1;
            }
        }
        if lost > 0 {
            self.dropped.fetch_add(lost, Ordering::Relaxed);
        }
    }
}

/// What a finished recording holds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Summary {
    pub frames: u64,
    /// frames lost because the writer fell two seconds behind
    pub dropped: u64,
}

/// The app's end: how far the recording has got, and its end.
pub struct Recorder {
    frames: Arc<AtomicU64>,
    dropped: Arc<AtomicU64>,
    thread: Option<JoinHandle<io::Result<u64>>>,
}

impl Recorder {
    /// Start a WAV in `file` (empty, writable and seekable). Give the [`Tap`] to the engine.
    pub fn start(file: File) -> io::Result<(Recorder, Tap)> {
        let mut w = BufWriter::with_capacity(64 * 1024, file);
        w.write_all(&header(0))?;
        let (prod, cons) = rtrb::RingBuffer::new(RING);
        let frames = Arc::new(AtomicU64::new(0));
        let dropped = Arc::new(AtomicU64::new(0));
        let f = frames.clone();
        let thread = std::thread::Builder::new()
            .name("harbor-recorder".into())
            .spawn(move || write(w, cons, f))?;
        Ok((Recorder { frames, dropped: dropped.clone(), thread: Some(thread) }, Tap { ring: prod, dropped }))
    }

    /// Frames written so far.
    pub fn frames(&self) -> u64 { self.frames.load(Ordering::Relaxed) }

    /// Wait for the writer to finish the file. Only returns once the engine has dropped the tap.
    pub fn finish(mut self) -> io::Result<Summary> {
        let frames = match self.thread.take().map(|t| t.join()) {
            Some(Ok(r)) => r?,
            _ => return Err(io::Error::other("the recording's writer failed")),
        };
        Ok(Summary { frames, dropped: self.dropped.load(Ordering::Relaxed) })
    }
}

fn write(mut w: BufWriter<File>, mut ring: rtrb::Consumer<[i16; 2]>, frames: Arc<AtomicU64>) -> io::Result<u64> {
    let mut n = 0u64;
    let mut bytes = Vec::with_capacity(16 * 1024);
    loop {
        // checked before draining, so whatever the engine pushed before letting go gets written
        let done = ring.is_abandoned();
        while let Ok(chunk) = ring.read_chunk(ring.slots().min(4096)) {
            if chunk.is_empty() {
                break;
            }
            let take = (chunk.len() as u64).min(MAX_FRAMES - n) as usize;
            let (a, b) = chunk.as_slices();
            bytes.clear();
            for f in a.iter().chain(b).take(take) {
                bytes.extend_from_slice(&f[0].to_le_bytes());
                bytes.extend_from_slice(&f[1].to_le_bytes());
            }
            chunk.commit_all();
            w.write_all(&bytes)?;
            n += take as u64;
            frames.store(n, Ordering::Relaxed);
        }
        if done {
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let mut file = w.into_inner().map_err(|e| e.into_error())?;
    file.seek(SeekFrom::Start(0))?;
    file.write_all(&header(n))?;
    file.flush()?;
    file.sync_all()?;
    Ok(n)
}

/// A canonical 44-byte WAV header for `frames` of 16-bit stereo at [`RATE`].
fn header(frames: u64) -> [u8; HEADER as usize] {
    let data = (frames * 4) as u32;
    let mut h = [0u8; HEADER as usize];
    h[0..4].copy_from_slice(b"RIFF");
    h[4..8].copy_from_slice(&(36 + data).to_le_bytes());
    h[8..12].copy_from_slice(b"WAVE");
    h[12..16].copy_from_slice(b"fmt ");
    h[16..20].copy_from_slice(&16u32.to_le_bytes());
    h[20..22].copy_from_slice(&1u16.to_le_bytes()); // PCM
    h[22..24].copy_from_slice(&2u16.to_le_bytes());
    h[24..28].copy_from_slice(&RATE.to_le_bytes());
    h[28..32].copy_from_slice(&(RATE * 4).to_le_bytes());
    h[32..34].copy_from_slice(&4u16.to_le_bytes());
    h[34..36].copy_from_slice(&16u16.to_le_bytes());
    h[36..40].copy_from_slice(b"data");
    h[40..44].copy_from_slice(&data.to_le_bytes());
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp(name: &str) -> std::path::PathBuf {
        std::env::temp_dir().join(format!("harbor-{}-{name}.wav", std::process::id()))
    }

    #[test]
    fn writes_a_wav_with_its_sizes_filled_in() {
        let path = temp("wav");
        let file = File::options().read(true).write(true).create(true).truncate(true).open(&path).unwrap();
        let (rec, mut tap) = Recorder::start(file).unwrap();
        let samples: Vec<i16> = (0..2000).map(|i| i as i16 - 1000).collect();
        tap.push(&samples);
        tap.push(&samples[..4]);
        drop(tap);
        let s = rec.finish().unwrap();
        assert_eq!(s, Summary { frames: 1002, dropped: 0 });

        let b = std::fs::read(&path).unwrap();
        std::fs::remove_file(&path).ok();
        assert_eq!(b.len(), 44 + 1002 * 4);
        assert_eq!(&b[0..4], b"RIFF");
        assert_eq!(u32::from_le_bytes(b[4..8].try_into().unwrap()), 36 + 1002 * 4);
        assert_eq!(u32::from_le_bytes(b[24..28].try_into().unwrap()), 44_100);
        assert_eq!(u32::from_le_bytes(b[40..44].try_into().unwrap()), 1002 * 4);
        assert_eq!(i16::from_le_bytes([b[44], b[45]]), -1000);
        assert_eq!(i16::from_le_bytes([b[46], b[47]]), -999);
    }

    #[test]
    fn a_full_ring_drops_frames_instead_of_waiting() {
        let (prod, _cons) = rtrb::RingBuffer::new(4);
        let dropped = Arc::new(AtomicU64::new(0));
        let mut tap = Tap { ring: prod, dropped: dropped.clone() };
        tap.push(&[0; 12]);
        assert_eq!(dropped.load(Ordering::Relaxed), 2);
    }
}
