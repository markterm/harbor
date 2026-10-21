# Harbor — Implementation Plan

**Harbor** is a native Android app (phone + tablet) that runs the M-VAVE FM-1 emulator from
[joakimeriksson/fm1-emu](https://github.com/joakimeriksson/fm1-emu), plays its firmwares in
real time, and takes MIDI in from devices, other apps and a computer over USB (MIDI out is
out of scope). SMK-37 support follows in v1.1.

*Name:* the FM-1's community firmwares are mostly named after boats (Felucca, SLOOP, Skiff,
Dinghy, Jangada), and Harbor is where they all dock. Application id `app.harborsynth` (the code's package is `com.markwhite.harbor`).
The store listing says "plays M-VAVE FM-1 firmwares" (nominative use); M-VAVE isn't in the
app name or icon.

**Target devices:** modern phones and tablets only, meaning Android 12+ on a 2022-or-newer
upper-mid-range or flagship arm64 SoC (Snapdragon 8 Gen 1 / 7+ Gen 2, Tensor G2, Dimensity
8100/9000 class or newer).

UI/UX is specified separately in [`ui-spec.md`](ui-spec.md); this document covers architecture,
engineering milestones and risks.

---

## Status (2026-10-19, first working session)

**Built and verified**
- fm1-emu as a pinned submodule; the Android JIT patch (`patches/0001…`) applied. Upstream's
  fm1-core tests pass with it (33/33), and fm1-emu builds for `aarch64-linux-android`.
- `native/harbor-engine`, the host: machine thread running `lead` frames ahead, 44.1→48 kHz
  resampling in the output callback, per-source MIDI parsing (running status, SysEx, remap,
  held-note release), frame-stamped MIDI, the automatic clock, flash publishing after a quiet
  period, and `.fm1save` v1 (passes upstream's browser fixture).
  - 18 unit tests.
  - 4 tests on real Felucca: a key makes sound; MIDI reaches the jack; on a throttled "slow
    device" the clock steps down with flash kept; a fixed clock never steps.
- `native/harbor-jni` (`libharbor.so`): JNI surface and AAudio output (exclusive, falling back to
  shared).
- `native/harbor-bench` and `tools/m0-bench.sh`, the M0 benchmark. First phone numbers
  (2026-10-20, Pixel 7 Pro, `docs/m0-results/Pixel_7_Pro.tsv`): with the JIT every firmware
  tried keeps up at 240 MHz (Etch 1.22×, Felucca 1.52×, Salt 1.78×); interpreted, none does
  even at 120 MHz. Inside the app the JIT is on (`untrusted_app` allows RWX).
- The app, built with AGP 9.4 / Gradle 9.8 / Compose BOM 2026.09; minSdk 31, target 37.
  - All four Play layouts (C-P with a two-tier keybed, C-L, M fitted, E faithful plus a pane),
    checked by screenshot on the emulator.
  - Panel controls with gestures, haptics and TalkBack semantics. The LCD is pixel-exact and the
    LEDs are live.
  - Library: catalog, download, bring-your-own stock firmware, local `.fwsc`, update check.
  - Saves: autosave, Save now, export, share, import, reset, DX7 bank.
  - MIDI: sources, remap, activity, BLE scan and pair, USB-peripheral hint and guide, Panic.
  - Harbor as a MIDI device (one input port), a foreground playback service, the Audio &
    performance screen, Controls & display, Help, About, onboarding, and the Bluetooth latency
    warning.
- Tests:
  - 7 Kotlin unit tests (keybed geometry, layout modes, the perf banner, catalog parsing).
  - 1 instrumented test: another app opens "Harbor" and its note reaches the emulated jack
    (passes on the emulator).
- End to end on the emulator: Felucca downloads, boots and draws. A knob drag changes its CUT
  value on the LCD. The x86 emulator interprets, so the automatic clock steps 240 → 120 MHz and
  the banner explains that it still can't keep up. That is expected there.

**Deviations from §2 (deliberate, small)**
- SharedPreferences and JSON files instead of Room or DataStore, and a hand-rolled back stack
  instead of Navigation Compose. There was little state, so this meant fewer dependencies to
  pin; revisit if it grows.
- MIDI learn moved to v1.1, as in the UI spec.
- The JIT is probed once per process (`jit_usable`). On a device that refuses RWX memory Harbor
  boots interpreted instead of letting fm1-core retry the 64 MB mapping on every block.

**Not done yet / next**
1. **M0 on more phones.** Done on a Pixel 7 Pro (Tensor G2) and the upstream PR is open (see
   `docs/upstream-pr.md`). Still to run: a Snapdragon and a Dimensity device, ideally the floor
   of the target list, to set each firmware's starting clock.
2. Real MIDI hardware: a USB keyboard, a BLE controller, and USB peripheral mode against a laptop
   DAW. The code paths exist but only the virtual-device path is tested.
3. Latency measurement on a phone (touch-to-sound, ui-spec §10.4). The low-latency lead is a
   first guess (768 frames, about 17 ms).
4. `PerformanceHintManager` for the engine thread. The current hint is only a nice level, and
   Android may refuse it.
5. Panel polish: a bundled condensed silkscreen font, fine-adjust with a second finger, palm
   rejection, and screenshot tests per size class.
6. A signed release build and Play listing (M5).

**Found on Mark's Pixel 11 Pro (2026-10-19), to fix after the visual pass**
- [x] Felucca: ARP switched on and a key held, but nothing arpeggiates. Not a bug: in Felucca the
  ARP button opens the arp's page, and the arp runs only once that page's MODE (KNOB1) is turned
  from OFF. Checked in the emulator (`felucca_arpeggiates_a_held_key`: the output gates at the
  arp's rate).
- [x] Many catalog firmwares 404 on download (for example amb-1): 17 of fm1-emu's 47 package
  links point at versions the FM-1 Firmware Hub has since replaced. Harbor now merges the hub's
  `catalog.json` (bundled, cached, refreshed daily): listed firmwares take the hub's current
  package, checked against its sha256, falling back to fm1-emu's link; the hub's 30 other FM-1
  firmwares follow in a "More from the FM-1 Firmware Hub" section, marked as unchecked.
- [x] The automatic clock might step down on a one-off glitch (GC pause, app switch, thermal
  blip) and then stay down for good. Needs: ignore stalls while not in front or right after
  resume, demand sustained lateness, and step back up once there's lasting headroom (per
  session, not remembered for ever from one bad moment).
  Done: 6 s warm-up after a boot, resume or audio restart (the JIT is still compiling); 5 slow
  seconds in a row to step; a step becomes the firmware's boot clock only when a second session
  needs it too; 2 minutes without a step forgets it; 0.1.0's remembered clocks are reset once.
- [x] TRIBE 0.1 looked broken once, then worked after a restart: the emulator had got into a bad
  state. Add logging (engine state changes, boots, clock steps, audio restarts, halts, CPU
  faults) to logcat and a small in-memory ring that "Copy a report" includes.
  Done: `EventLog` (logcat tag "Harbor"), with a health line every 30 s, at once on dropouts,
  and a "looks stuck" warning when a running machine makes no progress for 3 s.
- [x] LEDs flicker in TRIBE and Xox. The firmware multiplexes and PWMs them, and a 30 Hz poll of
  the instantaneous level aliases. Average over the poll window and smooth (fast attack, slow
  release). Done in the engine (25 ms emulated windows, panel.rs scaling, fade), and buttons now
  light in proportion to brightness. Needs Mark's eyes on TRIBE and Xox to confirm.
- [x] Dropouts at 70–90% load (FoMni, CoralFlower) with the 768-frame lead. Next: a
  PerformanceHintManager session for the engine thread so it stays on a big core, and find why
  the UI's main thread still uses about a third of a core while the LCD animates (LCD copy per
  frame? the 10 Hz status recomposition?).
  Done: an ADPF hint session on the engine thread (`harbor-jni/src/hint.rs`; target 6 ms of work
  per 10 ms of audio) took FoMni from 72–93% to about 50% load with no dropouts. The UI's main
  thread was mostly the debuggable build's slow Kotlin: `VARIANT=profile tools/dev.sh run`
  installs release code signed with the debug key, where it is under 10%.
- [x] The settings screens look rough next to the new panel: give them the same visual pass.
  Done: condensed headings, grouped cards, a custom segmented control, amber switches, one-line
  links, monogram tiles in the library.
- [x] The clock-reduced snackbar never went away (an action snackbar defaults to indefinite): it
  now lasts a few seconds and has a close button.
- [x] Tablet landscape: hiding the pane now gives big keys (the upper panel held under half the
  height, the keybed full width, no rail); remembered. Tablet portrait's keybed takes about a
  third of the height instead of a fixed 220 dp.
- [x] The LCD between whole multiples of 240 px is drawn at the next multiple and scaled down on
  the GPU, so every LCD pixel is the same width (nearest-neighbour alone made strokes uneven).
- [x] Boot-time silence while the JIT warms up no longer shows as dropouts in the health log.
- [x] Audio recording: the red dot in the Play bar records the firmware's output (16-bit,
  44.1 kHz, MASTER included) to a WAV in Music/Harbor through MediaStore, no permission. The
  engine thread copies each chunk into a ring; a writer thread writes the file. A take keeps
  going in the background (the playback service starts for it), a killed take is recovered at
  launch, and the saved snackbar offers Share. Tested on a Pixel 7 Pro and in
  `records_what_the_firmware_plays`.
- [x] Found while testing recording: on a Pixel 7 Pro, Felucca drops to about 1.0x real time
  whenever Harbor isn't in front (a share sheet over it, or the background), so the automatic
  clock stepped down and remembered 200 MHz for the foreground too. Steps taken while Harbor
  isn't in front are no longer remembered, and choosing a clock by hand clears the memory.
  Still open: whether to ask for more CPU in the background (the ADPF session doesn't hold
  the engine on a big core there), and tablet layouts of the record button (checked on phones only).
- [x] Notes at the left edge were lost to the Back gesture (the 30 dp edge zone). The Play screen
  now hides the navigation bar (which lifts Android's 200 dp cap on gesture exclusion) and
  excludes the whole panel, keeping the full screen width.
- [x] Found on a second phone: a black LCD and silence after a relaunch, with "audio output
  closed: reopening" every second. The stream died at once on each reopen and every reopen asked
  for an exclusive stream again. A stream that dies within 3 s of opening, or won't start, now
  makes the next open ask for less (shared, then shared without low latency, `audio.rs`), retries
  back off, and after five a message says so. Not yet confirmed on that phone: its native log
  (`adb logcat -s harbor`) would say why the stream died.
- [x] Play builds (release, `BuildConfig.PLAY_CATALOG`) hide FM-1 Doom from the catalogue: Harbor
  is rated Everyone, and a shooter would make it Teen. Debug builds list it.
- [ ] FM-1 Doom's right fifth repeats its left fifth: a firmware bug (its 320→240 column map is
  `uint8_t`, so it wraps at 256), not Harbor's or fm1-emu's. Report it to zvenson/fm1doom.
- [ ] On a Pixel 7 Pro, Felucca drops frames in the background with "Keep playing in background"
  on (0.96x at 240 MHz, then 200 MHz); seen while filming Play's foreground-service video.
- [x] OberVave 1.5.3 halted about 8 ms after any key was released: `64 e8 5f bc`,
  `[r11+92] &= ~r12`, which fm1-isa's table couldn't decode because its corpus never had a
  register above r7 there. `patches/0002` widens the field (bits 24-27), and a machine test checks
  the decode. A halt now keeps its reason (instruction and pc, cores, time since boot), which goes
  into the event log and so into "Copy a report"; before, it was dropped.
- [ ] A sweep of the Hub's 79 packages (4 s each, interpreted, three keys held): CTL-1, MC-1 and
  386DX halt on other undecoded `0x?064` words (`2300f064`, `0e00f064`, `ae00f064`). Kawa halted
  at 829 ms with no input (fixed by `patches/0005`, below). Bubba, Melodee and Tonitor make no sound. 13 start
  the second core; the halts come from decoding, not from dual-core running. Report upstream
  (`docs/upstream-issue-decoder.md`) before patching more of the table locally.
- [x] Bundled Hub snapshot refreshed to 21 Oct (74 → 79 firmwares; the Hub's catalog.json grew
  new fields, which the parser ignores). fm1-emu's list hadn't changed.
- [ ] fm1-editor.com/firmware lists nine firmwares that neither the Hub nor fm1-emu carries yet
  (Bigorna, Gnomad, Haunted FM1, Modular One, Synthesthetic, Clipper, ESTAMPIE, FN ACID, Prysm):
  their packages are GitHub releases or installer pages. Harbor gets them once the Hub lists
  them (daily refresh, no app update); a third catalogue source would be a decision of its own.
- [x] Reported on a Xiaomi Mi 10T (Android 12): the app closed at once on every launch.
  `MidiHub.start()` called `MidiManager`'s transport-aware methods and `Bundle.getParcelable(key,
  Class)`, all API 33, so Android 12 (31/32) threw `NoSuchMethodError` from the process's first
  `onStart`. Below 33 it now uses the older calls and `BundleCompat`. Reproduced and checked on an
  API 31 emulator (`harbor_api31`); lint now has no `NewApi` errors, so keep it that way.
- [x] The Library shows each firmware's screenshot from the Hub (`img/<hub id>.png`, which its own
  page uses; catalog.json doesn't list them). `Screenshots` keeps them in the cache directory,
  revalidates weekly by ETag, caps them at 2 MB and decodes them to at most 800 px a side. Cards
  draw them at their own shape (square screens, 2:1 banners) up to 160 dp tall, details up to
  280 dp; a missing, broken or not-yet-loaded one leaves the card as it was. If the Hub adds an
  image field to catalog.json, `Catalog.hubImage` is the one place to change.
- [x] The firmware tests failed about one suite in three (`boots_draws_and_plays_a_key`,
  `records_what_the_firmware_plays`): Running, sound flowing, but `lcd_rev` stuck at 2. Not a
  hang: the machine is deterministic, and silent Felucca draws its splash (110 ms) and its screen
  (595 ms), each in 1-3 ms of partial frames, then holds still. The view samples the LCD every
  16 ms of wall time, so it sees two changes, and the tests' `lcd_rev > 2` passed only when a
  sample caught a frame half drawn. They now wait for `lcd_rev >= 2` (`drawn`) and print the
  engine's state when a wait times out; clean in 8 runs alone and 4 at double load.
- [x] Melodee 1.4.5 (Hub) never drew or sounded: 60 ms into boot it checks its supply setup by
  reading the P33 power registers back, and fm1-emu didn't model P33 (0x13E08/0x13E0C fell to
  "unknown", reads 0), so it parked itself in `fm1_core1_failure`. `patches/0003` models P33 as
  registers that read back what was written (and, or, xor; the RTC bank). A sweep of the Hub's 78
  packages with and without it changes only Melodee. On the Pixel 7 Pro it runs two cores at
  0.85x at 240 MHz with a key held; the automatic clock steps it to 200 MHz (1.16x, no late
  frames). Upstream as fm1-emu#34.
- [x] Panel finishes in the FM-1's case colours: Purple, Orange, Navy, Green and Red beside
  Graphite, Dusk and Arctic (the colours sellers list; keys stay cream). Orange is light enough
  that amber vanishes on it, so its LEDs are a warm white and its silk dark. Eight names don't
  fit `Segmented`, so the setting is a `SwatchChoice` (Common.kt): case-coloured discs that wrap.
- [x] DUELL 0.2.20 halted when HOME was pressed: `94 f1 13 13`, `r1 = r1 & ~(1 << r3) #`, the
  pair-head (`#`, f1) twin of fm1-isa's `r_mov_r_i_lsl_r_l4` (e1), missing from the table as
  `r_mov_r_i_lsl_r_5` is the and form's. `patches/0004` adds it (slots as its twin's); DUELL now
  takes HOME and every button, and a sweep of the Hub's packages is otherwise unchanged.
  Upstream as fm1-emu#35 (and 0002 as #33), each with an fm1-core test that runs the instruction.
- [ ] "The ARP button can be flickery": on the host (interpreted, the engine's 25 ms windows) the
  ARP LED is steady in all 78 packages; FiMba blinks it on purpose. Next: which firmware, and
  whether it is a blink or a shimmer; then check the LED's lit-time accounting with the JIT on a
  phone (cycles counted per block could make a dimmed LED's duty noisy).
- [ ] A fresh install on the Pixel 7 Pro (Android 16) asked for notification permission at first
  launch, before onboarding. Harbor's code asks only when "Keep playing in background" is turned
  on (`rememberBackgroundToggle`), so find what triggers it before the Play review, which
  `docs/play-store.md` describes the other way.
- [x] Reported from SLOOP while recording: "halted ... UnknownInstruction { raw: ffc022390001ff60 }
  at pc 0x020219c2", 6.7 s after boot. The word is in SLOOP 2.8 (isod89's GitHub release; the
  Hub has 2.7.1), at 0x020219c8: `60 ff 01 00 39 22`, `if ((r0 & 1) == 0) goto +17522`.
  fm1-isa splits this 6-byte bit-test branch into two rows, forward offsets under 16 KB (bits
  45-47 clear) and backward ones (bit 47 set), so a forward jump of 16-64 KB had no row. Kawa's
  boot halt was the same branch. `patches/0005` adds the row (after the near one, so its words
  decode as before); both jumps land where neighbouring branches go (SLOOP's function exit,
  which 11 other branches target; Kawa's, the instruction just before it). SLOOP 2.8 passes the
  firmware tests; Kawa now boots and draws (silent on a single key, but it's a tracker). A sweep
  of 91 packages (the Hub's, older and newer SLOOPs, Clipper, LCD-4, SLOOP ALG) halts only on CTL-1,
  MC-1 and 386DX, as before. Not yet upstream.

---

## 1. What we are building on

fm1-emu is a Rust workspace (MIT) that emulates the FM-1's JieLi AC791N SoC (pi32v2 CPU, dual
core, 240 MHz) closely enough to run firmware packages (`.fwsc`) unmodified:

| Crate | Role | Use on Android |
|---|---|---|
| `fm1-isa` | generated pi32v2 decoder | as is |
| `fm1-core` | CPU interpreter + **AArch64 JIT** (`jit.rs`, `jit_a64.rs`), WASM JIT | as is + small `cfg` patch (below) |
| `fm1-soc` | memory map, peripherals, LCD (240×240 RGB565), SPI flash, `.fwsc` unpacking, SMK-37 board | as is |
| `fm1-plugin/src/engine.rs` | machine on its own thread, running `lead` frames ahead, MIDI stamped by frame, flash-sector snapshots | **the model for our engine** |
| `fm1-plugin/src/savefile.rs` | portable `.fm1save` v1 format (shared with the browser) | reuse for import/export interop |
| `fm1-web` | flat `extern "C"` API (`fm1_boot_fwsc`, `fm1_render`, `fm1_key`, `fm1_enc`, `fm1_midi`, `fm1_lcd`, `fm1_leds`, `fm1_pads`, `fm1_analog`, flash restore/revision) | the shape of our JNI surface |
| `fm1-live/src/panel*.rs` | front-panel geometry & hit-test (key/encoder ids) | reference for control ids only; UI is Compose |
| `web/firmwares.json` | catalog of 47 community firmwares, downloaded from authors' sites | catalog source |

Findings from reading the code that shape the plan:

1. **The JIT is gated on `target_os = "linux" | "macos"`.** Rust's Android target is
   `target_os = "android"`, so an unpatched build silently falls back to the interpreter.
   The Linux path (RWX anonymous `mmap` + explicit D/I-cache maintenance for Cortex-A) is
   what Android needs; the patch is adding `"android"` to those `cfg`s.
   x86_64 has no JIT; we build it only for the dev emulator.
2. **Performance is the main risk.** Upstream reports 3–6× real time on Apple silicon with
   the JIT and that heavy firmwares reach only ~1.5×. Even modern phone cores are slower
   than an M-series Mac, so the heaviest firmwares may not keep real time. Milestone 0
   measures this before anything else is built.
3. **MIDI IN exists** (UART1 / TRS jack, `Action::Midi`, paced at 31 250 baud with a queue).
   MIDI OUT does not exist upstream (UART TX is discarded, USB-MIDI is not emulated) and is
   **out of scope** for this app.
4. Audio comes out of the emulated I2S DMA as **44.1 kHz stereo i16**; the machine is paced
   by demand, so the host must buffer ahead (as the plugin does).
5. Firmwares are **not redistributable by us**: upstream downloads each from its author at
   run time; M-VAVE stock firmware must be user-supplied (`FM-1.fwsc`). We follow the same
   policy.

---

## 2. Architecture

```
┌──────────────────────────── Kotlin / Compose (app) ────────────────────────────┐
│ UI: adaptive panel (FM-1 / SMK-37), firmware browser, saves, MIDI & audio      │
│     settings — per ui-spec.md, WindowSizeClass compact / medium / expanded     │
│ ViewModels ─ Repositories: Catalog, Firmware store, Saves (Room + files), Prefs│
│ MIDI: MidiManager (USB / BLE / virtual inputs) ─┐   FM1MidiDeviceService       │
│       BluetoothLeScanner pairing                │   (we appear as a MIDI device)│
│ PlaybackService (foreground, mediaPlayback) owns the engine lifetime           │
└──────────────┬───────────────────────────────────┬─────────────────────────────┘
               │ JNI (thin, no allocation on hot   │ MIDI bytes + nanoTime stamps
               │ paths; DirectByteBuffers for LCD) │
┌──────────────▼───────────────────────────────────▼─────────────────────────────┐
│ libfm1android.so  (Rust, cdylib)                                               │
│  Engine thread ── fm1-core (JIT) + fm1-soc ── runs `lead` frames ahead         │
│     ▲ cmds (SPSC): key, enc, pot, analog, velocity, boot, snapshot             │
│     ▲ midi_in (SPSC, frame-stamped)                                            │
│     ▼ audio ring (rtrb, stereo f32)                                            │
│  AAudio callback (ndk crate, LowLatency, Exclusive) — pops ring only           │
│  View state (LCD fb + revision, LEDs, pads, speed, xruns) — seqlock / mutex    │
└────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Native engine (`crates/fm1-android`)

- Lives in **our repo**; fm1-emu is a **git submodule pinned to a commit**, pointing at our
  fork until the upstream PR (§2.6) is merged, then back at upstream.
- `engine.rs` is copied and adapted inside `fm1-android` (MIT, attribution kept). We don't
  ask upstream to restructure anything.
- Engine = `engine.rs` adapted: own thread, keeps an audio ring `lead` frames ahead, MIDI
  events carry the frame they are due at; flash changes detected by sector diff against
  pristine flash, quiet-period snapshot before save/export/firmware switch.
- Thread priority: engine thread at `THREAD_PRIORITY_URGENT_AUDIO`-equivalent
  (`setpriority`), pinned preference to big cores via `sched_setaffinity` hint when CPU
  info is available; request `PerformanceHintManager` (API 31+) sessions for the engine
  thread so DVFS doesn't starve it.
- Audio: AAudio via the `ndk` crate (`ndk::audio`), 44.1 kHz requested (device may resample
  — accept 48 kHz and resample in-engine with a small polyphase/linear resampler if the
  stream refuses 44.1), `PerformanceMode::LowLatency`, `SharingMode::Exclusive` with shared
  fallback; handle `ErrorDisconnected` (headphones/USB unplug) by reopening.
  The callback only copies from the ring; underruns count as `starved` for the UI.
- `lead` adapts: start ~2× burst size, grow on repeated underruns, surface it as "latency".
- **Automatic clock:** a firmware that can't keep real time at 240 MHz runs at a reduced
  emulated clock (upstream's `--mhz`) instead of stuttering. The audio rate stays 44.1 kHz;
  the firmware just gets fewer CPU cycles per audio block.
  - The engine measures speed continuously (emulated time ÷ wall time, as the plugin's
    `View::speed`). If it stays under ~1.15× with repeated underruns, the engine steps the
    clock down (240 → 200 → 160 → 120 MHz) and reboots the firmware from current flash,
    after a snapshot so patches aren't lost. It never steps the clock up on its own.
  - The chosen clock is remembered per firmware hash per device, so later launches start at
    it with no stutter. A one-time line in the UI says the firmware is running at a reduced
    clock; settings let the user reset to 240 MHz or pick a clock by hand.
  - The trade-off: with fewer cycles, a firmware may drop voices or effects under heavy
    load. That's the firmware's own behaviour on a slower CPU, and better than the host
    starving the audio.
- JIT failure (`CodeCache::new` → `None`, e.g. a device whose SELinux denies `execmem`)
  falls back to the interpreter; report it in the diagnostics screen. If RWX proves
  unreliable on some vendors, switch the code cache to a **dual-mapped memfd** (RW view +
  RX view) — keeps W^X and works under stricter policies.

### 2.2 JNI surface (`Fm1Native.kt` ↔ Rust, `jni` crate)

Coarse, explicit calls modelled on `fm1-web`'s exports:

```
create(sampleRate, lead) / destroy()
boot(pkg: ByteArray, sectors: Array<Sector>?, mhz): BootResult
key(id, down) · enc(id, clicks) · pot(value) · analog(idx, value) · velocity(us)
midiIn(bytes: ByteArray, offset, len, timestampNanos)        // from MidiReceiver
lcd(): DirectByteBuffer (RGB565, 240×240) + lcdRevision()
leds(): FloatArray(41) · pads(): IntArray(16) · board(): Int
stats(): speed, starved, late, latencyFrames, jitActive
snapshot(): Array<Sector>   (async, completes after flash quiet)
```

UI polls view state at display refresh (Choreographer / `withFrameNanos`), copies the LCD
only when `lcdRevision` changes, and draws it into an `ImageBitmap` (nearest-neighbour
scaling). No per-sample JNI traffic.

### 2.3 MIDI

**In (host → emulator)** — Kotlin `android.media.midi` (works on minSdk, no AMidi needed;
the latency cost of one JNI hop per message is negligible):
- Enumerate `MidiManager` devices (USB host, BLE, other apps' virtual ports), open selected
  output ports, connect a `MidiReceiver` that forwards bytes + `timestamp` to
  `midiIn()`. Rust converts `System.nanoTime` stamps to engine frames (`now + lead`) so
  timing jitter from the JNI/Binder path is absorbed like the plugin does.
- BLE MIDI: scan for service UUID `03B80E5A-EDE8-4B33-A751-6CE34EC4C700`, open with
  `MidiManager.openBluetoothDevice`. Runtime permissions: `BLUETOOTH_SCAN`
  (`neverForLocation`) / `BLUETOOTH_CONNECT`.
- Per-device enable, channel filter/remap (stock firmware listens on ch 2, Felucca 1–4),
  MIDI activity indicator; MIDI learn (v1.1) mapping CCs → panel knobs/buttons (app-side
  mapping, injected as `enc`/`key`/`pot` commands — useful for firmwares with no CC support).
- Running-status and SysEx handled by the emulated UART (bytes are fed raw); the app only
  splits/filters for channel remap.

**Exposed to other apps (system MIDI device)** — `FM1MidiDeviceService : MidiDeviceService`
declared in the manifest with `res/xml/midi_device_info.xml`:
- **Input port "FM-1 In"**: other apps/DAWs play the emulator (same path as above).
- Input port only: no output port, MIDI thru or firmware MIDI out (out of scope).
- The service binds to the running engine via `PlaybackService`; if no firmware is running
  it boots the last-used firmware so a DAW can play it headless.
- Firmwares that are MIDI controllers (CTL-1, SMK-37 stock) run but send nothing anywhere;
  the catalog should flag them.

**USB peripheral MIDI (phone as a USB-MIDI device for a computer)**: cheap, in v1.
- With USB set to "MIDI" in the system's USB preferences, the kernel's MIDI gadget makes the
  phone a class-compliant USB-MIDI device on the computer, and Android shows the link in
  `MidiManager` as a `TYPE_USB` device ("USB Peripheral Port"). We open it like any other
  input, so there's no new MIDI code: notes from the computer's DAW go into the emulator.
- We can't change the USB mode ourselves (it's a system setting). When USB is connected but
  no MIDI peripheral device is present, the app shows a hint explaining how to switch the
  mode, opening system settings (there's no public intent for the USB preferences screen).
- Audio still plays on the phone's speaker or headphones. Sending audio back to the
  computer over the same cable (USB audio gadget) isn't available to apps.
- Some OEM builds hide or remove the MIDI USB mode; test on Pixel and Samsung and list it
  as "supported where the phone offers USB MIDI mode".
- Estimate: 2–3 days (detection, hint UX, device-matrix testing).

### 2.4 Storage, firmware and saves

- **Catalog**: bundle a snapshot of `web/firmwares.json` in assets (SMK-37 catalog in v1.1),
  refresh from the upstream site with ETag on launch; never host packages ourselves.
- **Packages**: downloaded with WorkManager into app-private storage, keyed by **SHA-256 of
  the whole `.fwsc`** (as upstream); verify hash/size, then a trial boot before marking
  installed. User-supplied packages (M-VAVE stock) via Storage Access Framework
  (`ACTION_OPEN_DOCUMENT`), copied in.
- **Flash state**: per package hash, sparse 4096-byte changed sectors (as
  `wasm-persistence-plan.md`), stored as files + Room index, written atomically
  (temp + rename). Autosave after flash quiet period and on `onStop`/service stop;
  "Save now", "Reset saved flash".
- **Import/export** `.fm1save` v1 via SAF — byte-compatible with the browser and plugin
  (port/reuse `savefile.rs`; add a round-trip test against
  `crates/fm1-plugin/tests/fixtures/browser-v1.fm1save`).
- DX7 `.syx` bank import for stock firmware (`fm1_load_syx` equivalent).

### 2.5 App shell

- Kotlin, Jetpack Compose, Material 3, single-activity, Navigation Compose, Hilt, Room,
  DataStore, WorkManager, kotlinx.serialization, coroutines/Flow.
- **minSdk 31** (Android 12: `PerformanceHintManager`, new Bluetooth permissions, mature
  AAudio), target latest. Release ABI: `arm64-v8a` only (JIT). `x86_64` only in debug
  builds, for the emulator (interpreter). No 32-bit ARM.
- **Phone and tablet as first-class**: `WindowSizeClass`-driven layouts (compact /
  medium / expanded), landscape-first panel on phones, list-detail for firmware browser on
  tablets, foldable posture handling (Jetpack WindowManager) as a stretch. Multi-touch on
  keys/knobs with per-pointer tracking (chords, holding a button while turning a knob).
- `PlaybackService` (foreground, `mediaPlayback` type) keeps audio and the MIDI device alive
  in background / when a DAW is driving us; media notification with stop.
- Audio focus + `ACTION_AUDIO_BECOMING_NOISY` handling; audio device routing listener.
- Microphone (April OS sampling) — later; would need emulated ADC/mic input path.

### 2.6 Upstream contribution

One self-contained PR to joakimeriksson/fm1-emu, with no coordination beyond it: we don't
discuss plans with the author ahead of time and nothing depends on it being accepted.

- **Content:** add `target_os = "android"` beside `"linux"` in the JIT `cfg`s
  (`fm1-core/src/lib.rs`, `jit.rs`: `MAP_FLAGS`, `flush_icache`, `CodeCache::write`).
  Optionally add an `aarch64-linux-android` `cargo check` job to CI. Nothing app-specific.
- **When:** after M0 has shown that the JIT works on real devices inside an app process, so
  the PR carries on-device evidence (×-real-time table, interpreter vs JIT).
- **Shape:** match upstream style (dense explanatory comments, no new dependencies), one
  commit, a description with the measurements, and `cargo test` passing on host.
- **If it isn't merged**, we keep the patch on our fork; it's a few lines, so rebasing is
  cheap.

### 2.7 Build & CI

- Gradle (Kotlin DSL) + `cargo-ndk` invoked from a Gradle task (or
  `org.mozilla.rust-android-gradle`), outputs into `jniLibs/`. Pin Rust toolchain
  (`rust-toolchain.toml`) and NDK version.
- GitHub Actions: `cargo test` (host) for engine + savefile; `cargo ndk build`; Gradle
  `assembleRelease`, unit tests, Compose UI tests; screenshot tests (Roborazzi or Paparazzi)
  for each size class; lint. Emulator instrumentation tests on x86_64 (interpreter);
  performance tests on a physical arm64 device (Firebase Test Lab or a self-hosted phone).
- Release: Play internal track; R8; native debug symbols uploaded.

---

## 3. Milestones

Each milestone ends with something runnable on a device.

### M0 — Feasibility spike (go/no-go) — ~1 week
- Patch `cfg`s for `target_os = "android"`; build upstream `fm1-emu` batch runner for
  `aarch64-linux-android`; run via `adb shell` on 2–3 modern devices (a current flagship,
  a 2022 flagship such as Snapdragon 8 Gen 1 / Tensor G2, an upper-mid-range phone) and
  a tablet.
- Measure real-time factor for Felucca, SLOOP, OberVave, sloopDX, FM-106, stock (both
  cores) with JIT on/off and at reduced clocks. Confirm RWX mmap works under app SELinux
  domain (run inside a debug app, not just `adb shell`, since the domains differ).
- **Exit**: table of ×-real-time per device/firmware; confirmation that the target device
  class keeps real time on the main firmwares; decision on whether a memfd dual-map code
  cache is needed; **upstream PR opened**
  with the android `cfg` patch and these measurements (§2.6).

### M1 — Sound out of a phone — ~2 weeks
- `fm1-android` crate: engine thread, ring, AAudio output, JNI `create/boot/key/enc/pot`.
- Debug-only Compose screen: LCD bitmap + crude buttons; boot a bundled-for-dev package.
- Underrun/latency stats; disconnect/reconnect handling.
- **Exit**: Felucca plays from on-screen keys with no audible dropouts on the oldest
  target device (2022 flagship class).

### M2 — Playable FM-1 app (MVP UI core) — ~3–4 weeks
- Adaptive FM-1 panel per ui-spec (phone portrait/landscape, tablet), multi-touch keys,
  knob gestures, buttons, LEDs, haptics, accessibility semantics.
- Firmware catalog + download + SAF import of `.fwsc`; installed-firmware management.
- Flash autosave/restore per package hash; Save now / Reset.
- PlaybackService + notification, audio focus, background behaviour.
- **Exit**: install → pick firmware → play → kill app → reopen with patches intact.

### M3 — MIDI in + system MIDI device — ~2–3 weeks
- MidiManager input (USB, BLE scan/pair, virtual), per-device enable, channel remap,
  activity indicator.
- USB peripheral MIDI: detection and the USB-mode hint (§2.3).
- `FM1MidiDeviceService` with input port; headless boot when a DAW connects.
- Frame-accurate timing test: scripted note stream vs. rendered onset positions.
- **Exit**: a USB keyboard, a BLE controller, another app (e.g. a sequencer) and a DAW on a
  computer over USB each play the emulator; jitter measured and within target.

### M4 — Saves interop — ~1 week
- `.fm1save` import/export, compatibility test with browser/plugin fixtures; DX7 `.syx`.

### M5 — Hardening & release — ~2 weeks
- Device matrix testing (Pixel, Samsung, OnePlus/Xiaomi, a tablet, a foldable).
- Performance governor work (PerformanceHint, clock fallback suggestion when starving),
  battery/thermal behaviour, crash/ANR monitoring, Play listing, licences/attribution
  screen (MIT notice for fm1-emu; each firmware's licence/author shown from the catalog).

Rough total for v1: **~11–14 engineer-weeks** for one strong Rust+Android engineer, less
with a second engineer on Compose UI in parallel from M2.

### v1.1 — SMK-37 and MIDI learn — ~3 weeks
- MIDI learn (ui-spec §11.4): app-side mapping of CCs/notes to encoders, MASTER and buttons,
  stored per firmware.
- SMK-37 panel (16 RGB pads, 8 knobs, 4 faders, pitch/mod wheels, 37 keys with velocity)
  per ui-spec; board picked automatically from the package identity (`Board::of_identity`).
- SMK-37 firmware catalog (genkigenki's list); tablet-first layout, phone landscape only.
- The engine and JNI already carry `analog`, `velocity` and `pads` from v1, so this is
  mostly UI work.

---

## 4. Testing strategy

- **Determinism**: emulation is deterministic given input timing, so record golden WAV
  hashes for scripted runs (`fm1-soc::script` `ms:` actions, incl. MIDI `ms:m903c64`) on the
  host and assert the Android build (interpreter and JIT) produces identical audio.
- Engine unit tests on host: ring/lead logic, MIDI stamping, sector diff/restore, savefile
  round-trip with upstream fixtures.
- Instrumented tests: boot each catalog firmware for N seconds without halting (nightly,
  needs network or cached packages).
- Compose UI tests + screenshot tests per window size class and font scale; TalkBack checks.
- Manual latency measurement (loopback / WALT-style) per device class.

---

## 5. Risks & mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Phones can't run heavy firmwares in real time | core value | M0 gate; modern-device floor; automatic reduced clock per firmware; dual-core emulation on two threads (upstream runs both cores on one thread today) as a later optimisation |
| JIT denied on some devices (execmem) | ~3–5× slower | interpreter fallback already exists; memfd dual-mapping |
| AAudio refuses 44.1 kHz / high-latency OEM paths | glitches, latency | in-engine resampler; adaptive lead; shared-mode fallback |
| Firmware licensing / redistribution | store compliance | never bundle packages; download from authors as upstream does; stock firmware BYO |
| OEM hides USB MIDI mode | no USB-from-computer path on some phones | document per OEM; BLE MIDI and other apps still work |
| Upstream moves fast (daily commits) | merge pain | pinned submodule; our only patch is the small android `cfg` PR; engine code copied into our crate rather than depending on upstream internals |
| Background audio killed by OEM battery managers | DAW use breaks | foreground service, documentation, "don't optimise" prompt on known OEMs |

---

## 6. Open decisions

Decided: name **Harbor** (`app.harborsynth`; first `com.markwhite.harbor`); modern phones only (Android 12+,
2022+ upper-mid/flagship arm64); SMK-37 in v1.1; USB peripheral MIDI in v1; no MIDI out;
one upstream PR, no coordination; firmwares that can't keep real time run at an automatically
reduced clock (§2.1).

No open decisions remain; M0's measurements set the starting clock for each firmware.
