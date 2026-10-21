# AGENTS.md

Notes for coding agents (and people) working on Harbor: what it is, how to build and check it,
and the rules that aren't obvious from the code. Read `README.md` first; the product spec is
`docs/ui-spec.md` and the plan, status and open issues are in `docs/implementation-plan.md`.

## What this is

The application id (Play, devices, adb) is `app.harborsynth`; the code's package stays
`com.markwhite.harbor`.

Harbor is an Android app (phone and tablet, minSdk 31, arm64) that runs M-VAVE FM-1 firmwares
unchanged on [fm1-emu](https://github.com/joakimeriksson/fm1-emu), a Rust emulator of the FM-1's
JieLi SoC with an AArch64 JIT. The app draws the FM-1's panel, plays its audio, takes MIDI in
(USB, Bluetooth, other apps, a computer over USB) and shows up as a MIDI device itself.

```
app/                      Kotlin + Jetpack Compose (Material 3)
  engine/                 Harbor (the one emulator: docking, clock memory, saves, status loop),
                          NativeEngine (JNI), EventLog
  data/                   Catalog, FirmwareStore, SaveStore, Settings (SharedPreferences),
                          Recordings (WAVs in Music/Harbor through MediaStore)
  midi/                   MidiHub (sources), HarborMidiService (Harbor as a MIDI device)
  ui/panel/               the FM-1 panel: Controls (knobs, buttons, LCD), Keybed, Finish
                          (materials, shadows, case), PanelIcons, Fm1Panel (geometry)
  ui/                     PlayScreen (layout modes), HarborRoot (navigation), Chrome
  ui/screens/             Library, Saves, MIDI, Audio, Controls/Help/About; Common holds the
                          shared pieces (SubScreen, Section, InfoCard, SwitchRow, Segmented)
native/
  harbor-engine/          plain Rust, testable anywhere: machine thread, audio ring, resampler,
                          MIDI parsing, automatic clock, LEDs, .fm1save, recorder (WAV writer)
  harbor-jni/             libharbor.so: JNI bindings, AAudio output, ADPF hint session
  harbor-bench/           the M0 speed benchmark
third_party/fm1-emu/      upstream, a pinned git submodule, with patches/ applied
tools/                    dev.sh, m0-bench.sh, apply-patches.sh
```

## Build, run, test

The dev machine is Windows; the scripts are POSIX shell (Git Bash). JDK 17+, Android SDK with
NDK 29.0.14206865, Rust with the `aarch64-linux-android` and `x86_64-linux-android` targets, and
`cargo-ndk`. Cargo lives in `~/.cargo/bin`; put it on `PATH` in a fresh shell.

```sh
git submodule update --init && tools/apply-patches.sh   # idempotent
./gradlew assembleDebug                    # runs cargo-ndk first (task cargoNdk)
tools/dev.sh run                           # build, install, start; ANDROID_SERIAL picks a device
VARIANT=profile tools/dev.sh run           # release code, debug-signed: use this to judge speed
tools/dev.sh shot NAME                     # screenshot to build/shots/NAME.png
# profile builds only: record the firmware's output without the record button (for filming)
adb shell am broadcast -n app.harborsynth/com.markwhite.harbor.DemoCapture -a com.markwhite.harbor.demo.CAPTURE --es name take1
adb logcat -s Harbor                       # the engine's event log (boots, clock, health)

cd native && cargo test --release -p harbor-engine
HARBOR_TEST_FWSC=/path/felucca-1.6.1.fwsc cargo test --release -p harbor-engine --test firmware
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest        # needs a device
```

The firmware tests skip silently without `HARBOR_TEST_FWSC` (they report "6 passed" in 0.00 s);
run them with a real package when touching the engine. Felucca is at
`https://thefirmwarehub.com/fw/felucca-1.6.1.fwsc`.

On Git Bash, `adb shell` paths need `MSYS_NO_PATHCONV=1` (see `tools/m0-bench.sh`'s `hostpath`).

## Rules

- **No firmware in the repo or the APK.** Each package is its author's: Harbor downloads from
  the catalog's URLs (`app/src/main/assets/firmwares.json`, refreshed from fm1-emu's site), and
  M-VAVE's stock firmware is bring-your-own. Tests take a package from the environment.
- **fm1-emu is upstream.** Don't edit `third_party/fm1-emu` in place: change it through a
  numbered patch in `patches/` (keep it applying to the pinned commit) and keep the set small.
  Today: 0001 enables the JIT on Android (`docs/upstream-pr.md` holds the prepared upstream PR),
  0002 decodes and-not with a register above r7, 0003 models the P33 power registers. Opening it publishes under the owner's GitHub account, so it waits for their go-ahead.
- **The audio callback is real time.** `AudioOut::render` and the AAudio callback must not
  allocate, lock anything contended or log. The engine thread runs ahead of the output by
  `lead` frames through an `rtrb` ring; MIDI is stamped with CLOCK_MONOTONIC and applied at its
  frame.
- **Firmware behaviour belongs to the firmware.** The panel passes presses, releases, turns and
  the MASTER pot through as the hardware would; don't add app-side meaning to buttons.
- **Recording taps the engine thread, not the callback.** The firmware's 16-bit frames go into
  a ring (`recorder::Tap`, never blocking; frames drop if the writer falls 2 s behind) and a
  writer thread of its own writes the WAV. A take killed mid-way is recovered at the next launch.
- **Saves are the firmware's flash.** Harbor stores changed 4 KB sectors per package hash and
  interoperates with fm1-emu's `.fm1save`; never move a save between package versions.
- **The automatic clock** (240 → 200 → 160 → 120 MHz) steps only after a warm-up and sustained
  slowness, and a step becomes a firmware's boot clock only when a second session needs it, and
  only while Harbor is in front (behind a share sheet or in the background Android gives it
  less CPU).
  Keep it hard to trigger by accident: a step reboots the firmware.

## UI and performance

- The look follows the hardware: graphite case, glossy knobs, rubber buttons whose LED glow
  follows the LED's real brightness, cream silicone keys in a recessed tray, a bezelled LCD.
  Colours live in `Finish` (panel) and `Tokens` (app chrome); type is Barlow Condensed for
  silkscreen and headings, the system face for body text.
- The firmware redraws its LCD up to 60 times a second, and nothing else should redraw with it.
  The LCD has its own layer; static parts (knobs, buttons, keybed, case) are cached with
  `graphicsLayer(compositingStrategy = Offscreen)`; LEDs are one state per line, read where
  they're drawn. Passing a whole changing array or status object into the panel re-records
  everything: check `adb shell dumpsys gfxinfo app.harborsynth` after UI changes.
- Judge speed on the `profile` variant: a debuggable build runs Compose several times slower.
- The Play screen hides the navigation bar, which lifts Android's 200 dp cap on system gesture
  exclusion, and the panel excludes edge swipes so keys and knobs at the screen edge work.
- Phone portrait, phone landscape, tablet portrait and tablet landscape each have a layout
  (`layoutModeOf`); check all four when changing the panel.

## Style

- Kotlin and Rust both: comments say why, in plain sentences, at the density of the code around
  them. KDoc on public types and anything non-obvious; no banner comments beyond the existing
  `// ----- section` dividers.
- User-facing text is short and concrete, British spelling ("licence", "colour"), no jargon
  where a word will do. The ui-spec's copy section is the reference.
- Prefer editing the shared pieces (`ui/screens/Common.kt`, `ui/panel/Finish.kt`) over one-off
  styling in a screen.
- Keep `docs/implementation-plan.md`'s status section current when you finish or find something.
