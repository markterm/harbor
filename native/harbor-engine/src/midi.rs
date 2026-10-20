//! MIDI from one source, made safe to merge with others into the FM-1's single MIDI IN jack:
//! every message leaves complete and with its own status byte (no running status across
//! sources), SysEx leaves whole, real-time bytes leave at once, a channel remap moves channel
//! messages, and the notes a source holds are tracked so they can be released when it goes.

/// The longest SysEx passed on; a longer one is dropped (the jack's queue holds about a second).
const MAX_SYSEX: usize = 3125;

#[derive(Default)]
pub struct MidiParser {
    /// Move all channel messages to this channel (0..15).
    pub remap: Option<u8>,
    status: u8,
    data: [u8; 2],
    have: usize,
    sysex: Option<Vec<u8>>,
    /// notes held after the remap, per channel
    held: [u128; 16],
}

/// Data bytes a status byte takes (None: SysEx start or a byte that carries none).
fn data_len(status: u8) -> usize {
    match status {
        0x80..=0xbf | 0xe0..=0xef | 0xf2 => 2,
        0xc0..=0xdf | 0xf1 | 0xf3 => 1,
        _ => 0,
    }
}

impl MidiParser {
    pub fn new(remap: Option<u8>) -> Self { MidiParser { remap, ..Default::default() } }

    /// Parse `bytes`, emitting complete messages. Returns true if any message other than
    /// real-time (clock, active sensing…) went out, for the activity indicator.
    pub fn feed(&mut self, bytes: &[u8], out: &mut impl FnMut(&[u8])) -> bool {
        let mut active = false;
        for &b in bytes {
            if b >= 0xf8 {
                out(&[b]);
                continue;
            }
            if let Some(sx) = self.sysex.as_mut() {
                if b < 0x80 {
                    if sx.len() < MAX_SYSEX { sx.push(b); }
                    continue;
                }
                let mut sx = self.sysex.take().unwrap();
                if b == 0xf7 && sx.len() < MAX_SYSEX {
                    sx.push(0xf7);
                    out(&sx);
                    active = true;
                    continue;
                }
                // any other status ends an unterminated SysEx, which is dropped; fall through
            }
            if b >= 0x80 {
                match b {
                    0xf0 => {
                        self.sysex = Some(vec![0xf0]);
                        self.status = 0;
                    }
                    0xf7 => {} // a stray end of SysEx
                    0xf1..=0xf6 => {
                        self.status = 0; // system common cancels running status
                        if data_len(b) == 0 {
                            if b == 0xf6 { out(&[b]); active = true; }
                        } else {
                            self.status = b;
                            self.have = 0;
                        }
                    }
                    _ => {
                        self.status = b;
                        self.have = 0;
                    }
                }
                continue;
            }
            // a data byte
            if self.status == 0 {
                continue; // no status to go with it
            }
            self.data[self.have] = b;
            self.have += 1;
            if self.have == data_len(self.status) {
                self.have = 0;
                let mut status = self.status;
                if status < 0xf0 {
                    if let Some(ch) = self.remap { status = (status & 0xf0) | (ch & 0x0f); }
                }
                let msg = [status, self.data[0], self.data[1]];
                let n = 1 + data_len(status);
                self.track(&msg[..n]);
                out(&msg[..n]);
                active = true;
                if self.status >= 0xf0 {
                    self.status = 0; // system common messages don't run
                }
            }
        }
        active
    }

    fn track(&mut self, msg: &[u8]) {
        let ch = (msg[0] & 0x0f) as usize;
        match msg[0] & 0xf0 {
            0x90 if msg[2] > 0 => self.held[ch] |= 1u128 << msg[1],
            0x80 | 0x90 => self.held[ch] &= !(1u128 << msg[1]),
            0xb0 if msg[1] == 123 || msg[1] == 120 => self.held[ch] = 0,
            _ => {}
        }
    }

    /// Note-offs for every note this source still holds (it disconnected, or Panic).
    pub fn release_all(&mut self, out: &mut impl FnMut(&[u8])) {
        for ch in 0..16 {
            let mut notes = std::mem::take(&mut self.held[ch]);
            while notes != 0 {
                let n = notes.trailing_zeros() as u8;
                notes &= notes - 1;
                out(&[0x80 | ch as u8, n, 0]);
            }
        }
        self.status = 0;
        self.have = 0;
        self.sysex = None;
    }
}

/// All notes off and all sound off on every channel: the Panic button.
pub fn panic_messages() -> Vec<[u8; 3]> {
    (0..16u8).flat_map(|ch| [[0xb0 | ch, 123, 0], [0xb0 | ch, 120, 0]]).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(p: &mut MidiParser, bytes: &[u8]) -> Vec<Vec<u8>> {
        let mut v = Vec::new();
        p.feed(bytes, &mut |m| v.push(m.to_vec()));
        v
    }

    #[test]
    fn running_status_leaves_as_full_messages() {
        let mut p = MidiParser::new(None);
        assert_eq!(run(&mut p, &[0x90, 60, 100, 62, 100]), vec![vec![0x90, 60, 100], vec![0x90, 62, 100]]);
        assert_eq!(run(&mut p, &[0xc0, 5, 6]), vec![vec![0xc0, 5], vec![0xc0, 6]]);
    }

    #[test]
    fn messages_split_across_feeds_and_realtime_in_the_middle() {
        let mut p = MidiParser::new(None);
        assert!(run(&mut p, &[0x90, 60]).is_empty());
        assert_eq!(run(&mut p, &[0xf8, 100]), vec![vec![0xf8], vec![0x90, 60, 100]]);
    }

    #[test]
    fn clock_only_traffic_is_not_activity() {
        let mut p = MidiParser::new(None);
        assert!(!p.feed(&[0xf8, 0xf8, 0xfe], &mut |_| {}));
        assert!(p.feed(&[0xb0, 1, 64], &mut |_| {}));
    }

    #[test]
    fn remap_moves_channel_messages_only() {
        let mut p = MidiParser::new(Some(1));
        assert_eq!(run(&mut p, &[0x95, 60, 1, 0xf2, 1, 2]), vec![vec![0x91, 60, 1], vec![0xf2, 1, 2]]);
    }

    #[test]
    fn sysex_leaves_whole_and_unterminated_sysex_is_dropped() {
        let mut p = MidiParser::new(None);
        assert!(run(&mut p, &[0xf0, 1, 2]).is_empty());
        assert_eq!(run(&mut p, &[0xf8, 3, 0xf7]), vec![vec![0xf8], vec![0xf0, 1, 2, 3, 0xf7]]);
        assert_eq!(run(&mut p, &[0xf0, 1, 0x90, 60, 1]), vec![vec![0x90, 60, 1]]);
    }

    #[test]
    fn stray_data_without_status_is_ignored() {
        let mut p = MidiParser::new(None);
        assert!(run(&mut p, &[60, 100, 0xf2, 1, 2, 3]).len() == 1);
    }

    #[test]
    fn release_all_frees_exactly_the_held_notes() {
        let mut p = MidiParser::new(None);
        run(&mut p, &[0x90, 60, 100, 0x90, 64, 100, 0x90, 60, 0, 0x93, 127, 1]);
        let mut offs = Vec::new();
        p.release_all(&mut |m| offs.push(m.to_vec()));
        assert_eq!(offs, vec![vec![0x80, 64, 0], vec![0x83, 127, 0]]);
        let mut again = 0;
        p.release_all(&mut |_| again += 1);
        assert_eq!(again, 0);
    }
}
