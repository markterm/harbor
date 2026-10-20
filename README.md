# Harbor

Harbor plays M-VAVE FM-1 firmwares on Android phones and tablets, unchanged, on the emulator from
[joakimeriksson/fm1-emu](https://github.com/joakimeriksson/fm1-emu). Most of the FM-1's community
firmwares are named after boats (Felucca, SLOOP, Skiff, Dinghy), and Harbor is where they dock.
It plays from USB and Bluetooth MIDI, from other apps, and from a computer over USB, and other apps
see it as a MIDI device. Not made by or affiliated with M-VAVE.

- Product spec: [docs/ui-spec.md](docs/ui-spec.md)
- Implementation plan and status: [docs/implementation-plan.md](docs/implementation-plan.md)
- Upstream PR, prepared: [docs/upstream-pr.md](docs/upstream-pr.md)
- Play Store readiness and privacy policy: [docs/play-store.md](docs/play-store.md),
  [docs/privacy-policy.md](docs/privacy-policy.md)
- Notes for coding agents: [AGENTS.md](AGENTS.md)

## Layout

| Path | What |
|---|---|
| `third_party/fm1-emu` | the emulator (git submodule, pinned), with `patches/` applied |
| `native/harbor-engine` | the host: machine thread, audio ring, resampler, MIDI parsing, automatic clock, `.fm1save` (plain Rust, tested on any machine) |
| `native/harbor-jni` | `libharbor.so`: JNI bindings and the AAudio output |
| `native/harbor-bench` | the M0 benchmark (×-real-time per firmware and clock) |
| `app` | the Android app (Kotlin, Jetpack Compose, Material 3; minSdk 31) |
| `tools` | `dev.sh` (build, install, screenshot), `m0-bench.sh`, `apply-patches.sh` |

## Build

Needs JDK 17+, the Android SDK with NDK 29.0.14206865, Rust with
`rustup target add aarch64-linux-android x86_64-linux-android`, and `cargo install cargo-ndk`.

```sh
git submodule update --init
tools/apply-patches.sh
./gradlew assembleDebug          # builds native/ through cargo-ndk first
tools/dev.sh run                 # install on the connected device and start
```

`local.properties` needs `sdk.dir`, and `cargo=` if cargo isn't in `~/.cargo/bin`.

The debug APK carries `arm64-v8a` (phones; the emulator's JIT) and `x86_64` (the Android emulator,
interpreted and slow; the automatic clock steps down there). Release builds are arm64 only.

## Tests

```sh
cd native && cargo test --release -p harbor-engine                 # engine, MIDI, saves
HARBOR_TEST_FWSC=felucca-1.6.1.fwsc cargo test --release -p harbor-engine --test firmware
./gradlew testDebugUnitTest                                       # panel geometry, layouts, catalog
./gradlew connectedDebugAndroidTest                               # Harbor as a MIDI device (needs network)
```

Firmware packages aren't in this repository: each is its author's. Harbor downloads them from the
catalog's URLs (`app/src/main/assets/firmwares.json`, refreshed from fm1-emu's site), and M-VAVE's
own firmware is bring-your-own.

## M0: speed on real phones

```sh
tools/m0-bench.sh DIR_WITH_FWSC_FILES      # writes docs/m0-results/<model>.tsv
```
