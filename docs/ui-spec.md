# Harbor: UI/UX Product Spec (v1)

| | |
|---|---|
| Product | **Harbor** (`app.harborsynth`): plays M-VAVE FM-1 firmwares on Android |
| Status | Draft for eng/design review |
| Author | Product (PM) |
| Date | 2026-10-19 |
| Platform | Native Android, Kotlin + Jetpack Compose, Material 3. **minSdk 31 (Android 12+)** |
| Targets | 2022+ upper-mid and flagship **phones and tablets**, both first class; portrait and landscape; foldables nice to have |
| v1 device | **FM-1 only.** SMK-37 is v1.1 (§6.10) |
| Based on | [joakimeriksson/fm1-emu](https://github.com/joakimeriksson/fm1-emu) (MIT), studied at `master`, Oct 2026 |
| Out of scope | Engineering implementation plan (written separately) |

> **What Harbor emulates.** fm1-emu emulates the **M-VAVE FM-1**, a pocket 6-operator FM **synthesizer**
> (an early brief described a guitar pedal; that was wrong). It emulates at the chip level (two pi32v2 CPU
> cores, SoC peripherals, LCD, key matrix, encoders, audio DMA, MIDI UART), so it boots the device's own
> firmware packages (`.fwsc`) unchanged: about 47 community firmwares plus M-VAVE's stock firmware, which
> the user supplies. It also emulates the **SMK-37** keyboard, built on the same chip (Harbor v1.1).
> Most community firmwares are named after boats (Felucca, SLOOP, Skiff, Dinghy, Jangada). **Harbor** is where they dock.
>
> Core loop: **pick or load a firmware → play the emulated front panel → keep and back up what it saves.**

---

## Contents

1. [Product goals, users, non-goals](#1-product-goals-users-non-goals)
2. [What fm1-emu actually is (ground truth)](#2-what-fm1-emu-actually-is-ground-truth)
3. [Feature list and priorities](#3-feature-list-and-priorities)
4. [Information architecture and navigation](#4-information-architecture-and-navigation)
5. [Adaptive layout system](#5-adaptive-layout-system)
6. [Screen specs](#6-screen-specs) (6.10: SMK-37 panel, v1.1)
7. [Touch interaction details for the panel controls](#7-touch-interaction-details-for-the-panel-controls)
8. [Accessibility](#8-accessibility)
9. [Visual design direction](#9-visual-design-direction)
10. [Audio and latency UX](#10-audio-and-latency-ux)
11. [MIDI input and Harbor as a MIDI device](#11-midi-input-and-harbor-as-a-midi-device)
12. [Acceptance criteria (MVP)](#12-acceptance-criteria-mvp)
13. [Open questions for the eng lead](#13-open-questions-for-the-eng-lead)
- [Appendix A: control maps](#appendix-a-control-maps)
- [Appendix B: key copy strings](#appendix-b-key-copy-strings)
- [Appendix C: sources](#appendix-c-sources)

---

## 1. Product goals, users, non-goals

### 1.1 Problem

The FM-1 has an active firmware scene: grooveboxes, samplers, chord machines, ambient generators, a Juno-106
port, even Doom. fm1-emu already runs all of these in a browser, on desktop and as a DAW plugin. On
Android the web page is "too slow" (fm1-emu `PLAN.md` §5.13), it can't act as a MIDI device for other
apps or a computer, and a canvas scaled to fit a phone gives controls too small to touch.

### 1.2 Goals (v1)

| # | Goal | Measure |
|---|---|---|
| G1 | **Play any catalog firmware in a few taps**, with no hardware. | First sound under 60 s from first launch (Wi-Fi, default firmware). |
| G2 | **Feel like the device.** Same controls, same layout logic and labels, LCD pixel-exact, LEDs lit by the firmware. | FM-1 owners recognise it at a glance (usability test). |
| G3 | **Playable on touch at every size.** On phones the panel *re-flows* instead of shrinking. | No panel control target below §5.3 minimums on a 360 dp-wide phone. |
| G4 | **Be a good MIDI instrument.** Play from USB/BLE keyboards, from other apps, and from a computer DAW over the phone's USB cable; appear to other apps as a MIDI device they can play. | A DAW on the same phone, and one on a laptop over USB, can each play Harbor. |
| G5 | **Never lose a user's patches.** Auto-persist flash, with portable `.fm1save` backups that work with the web emulator and the plugin. | Round-trip import/export with the web emulator. |
| G6 | **Play smoothly, and be honest about how.** A firmware too heavy for this device runs at an automatically reduced emulated clock instead of stuttering, and Harbor says so once. | No sustained dropouts from emulator overload; every clock change is explained. |

### 1.3 Target users

1. **FM-1 owners**: try a firmware before flashing it, keep playing without the unit, back up setups.
2. **Prospective buyers and the curious**: "what is this thing and what do the firmwares do?"
3. **Firmware authors and testers**: quick checks on a phone or tablet; sharing a `.fm1save` that reproduces a bug.
4. **Mobile musicians**: use Harbor as a sound source from a USB/BLE keyboard, a mobile DAW or sequencer (FL Studio Mobile,
   Caustic, n-Track, Audio Evolution Mobile…), or a laptop DAW over USB.

### 1.4 Non-goals (v1)

- **SMK-37** (moves to v1.1, §6.10).
- **Any MIDI output**: no firmware MIDI out, no output port on Harbor's MIDI device, no MIDI thru, no routing of emulator MIDI to external devices.
- Audio or mic **input** of any kind; tuner; processing external audio.
- **Flashing a real FM-1** (installers use USB-MIDI SysEx; out of scope, and risky to hardware).
- Firmware development tools (debugger, GDB stub, flaky-core simulation).
- Hosting or bundling firmware packages. Harbor downloads each from its author's URL, as the web page does.
- **Inter-app audio** (routing Harbor's audio into another Android app) and **audio over USB** to a computer.
- Native per-firmware editors. The firmware's own LCD *is* the UI.
- Low-end devices and Android 11 or older: no UX compromises for them (minSdk 31).

---

## 2. What fm1-emu actually is (ground truth)

### 2.1 Emulated hardware: FM-1 (v1)

From `crates/fm1-live/src/panel.rs`, `crates/fm1-soc/src/periph.rs`, `docs/recon.md` and the web guide:

| Element | Count / spec | Emulator model | UI implication |
|---|---|---|---|
| LCD | 240×240 colour, RGB565 | `fm1-soc/src/lcd.rs` (ST7789-class) framebuffer | The firmware's whole UI. Render pixel-exact (nearest neighbour). |
| Note keys | 27 silicone pill keys, **F3–G5** (16 white, 11 black), **no velocity** | matrix ids 14–40 | Multi-touch, glissando. No velocity. |
| Encoders | 7 endless: **SELECT, PRESETS, ALGORITHM, KNOB1–4** | relative clicks (`fm1_enc(enc, clicks)`) | **Relative** controls with no absolute position or value; the value is on the LCD. |
| MASTER | 1 pot, absolute, 10-bit (SARADC ch 4) | `fm1_pot(0..1023)` | The only absolute knob; volume in most firmwares. |
| Buttons | 14 with LEDs: OCT−, OCT+, FX, SCL, ENV, LFO, EDIT, GLO, HOME, SAVE, ARP, SEQ, PLAY, REC | matrix ids 0–13; LED brightness per id (`fm1_leds`, 0..1 = share of time lit) | Momentary press/release. LEDs dim and blink by PWM. |
| Audio | Stereo I2S out, 44.1 kHz (engines mostly mono) | `fm1_out_l/r` | Output only. |
| MIDI IN | TRS jack, UART at 31,250 baud, about 1 s queue (3,125 bytes), excess dropped and counted | `fm1_midi(byte)` | Inbound MIDI is serialised at real DIN speed. |
| USB-C, BLE | present on the real unit | **not emulated** (USB register region stubbed) | Firmware features that use USB or BLE (e.g. Dinghy's USB audio) do nothing in Harbor. |
| Battery | ADC ch 3 | reads "full" | No UI. |
| CPU clock | 240 MHz nominal; host-selectable | web 60–240, plugin 240/320/400 | Clock picker plus performance UX. |

Physical layout (panel.rs, after Felucca's controls diagram): **top left** MASTER, SELECT, PRESETS, ALGORITHM
with OCT−/OCT+ under them; **centre** the LCD; **top right** KNOB1–4 over two rows of six buttons; **bottom** the
27-key keybed across the full width. The body is warm grey, the keys a muted rose, LEDs amber/green.

### 2.2 Emulated hardware: SMK-37 (v1.1)

From `crates/fm1-live/src/panel_smk.rs`, `crates/fm1-soc/src/smk.rs`: 240×240 LCD; **37 keys C3–C6** with two
contacts (velocity = time between them, 2–80 ms); **8 encoders** K1–K8 (one over each pad column); **16 RGB pads**
(2×8, analog pressure, WS2812 colours from `fm1_pads`); **4 faders**; **pitch wheel** (springs to rest 535/1023)
and **mod wheel**; **18 LED buttons** (KNOB BANK, FADER BANK, <, >, OCTAVE−, OCTAVE+, ARP, NOTE REPEAT, SCALE,
CHORD, GLOBE, BT, PATCH, PARA, FX, SEQ, SEQ PLAY, SEQ REC); pedal jack (analog 22). **No MIDI IN jack:** host MIDI
is mapped (notes C3–C6 → keys with velocity, pitch bend → pitch wheel, CC1 → mod wheel). Near-black body. The catalog has 15 SMK-37 firmwares.

### 2.3 Capability traceability and gaps

| Capability the UI needs | fm1-emu today | Gap ID |
|---|---|---|
| Boot catalog firmware from its package URL | Yes (`web/firmwares.json`, 49 FM-1 entries with `package`, `installer`, `embedded`, `byo`, `hub`, `license`, `about`, `repo`) | none |
| Bring your own stock firmware (`FM-1.fwsc`); DX7 `.syx` bank into slots 1–32 | Yes (`fm1_boot_fwsc`, `fm1_load_syx`) | none |
| Panel input: keys, buttons, encoders, pot | Yes (`fm1_key/enc/pot`) | none |
| LCD frames, LED brightness | Yes (`fm1_lcd` + revision counter, `fm1_leds`) | none |
| Flash persistence keyed by package SHA-256; portable `.fm1save` | Yes (web `persistence.js`, plugin `savefile.rs`; interop tested with fixture `browser-v1.fm1save`) | none |
| Real-time load and dropout metrics; clock change | Yes on web (host-side measurement, `main.js` perf monitor) | Android host reimplements |
| MIDI IN from host (any source), channel remap | Yes (UART model; remap is host-side) | none |
| MIDI IN overflow indicator | Counter exists (`Uart.dropped`) but is **not exported** | **GAP-1** |
| Firmware USB / BLE features | **No** | **GAP-2** (shown as a known limit only) |
| Audio input / mic (April OS samples via its installer page's mic bridge) | **No** | **GAP-3** |
| LCD content as text for screen readers | **No** (pixels only) | **GAP-4** |
| Per-firmware hints: MIDI channels (stock listens on ch 2; Felucca parts on 1–4), "no sound from a plain note-on" (ChoralRoot, Hortator, SLOOP ALG, X0X), "makes no sound of its own" (CTL-1), heaviness | Prose in the web guide only; speed data in `PLAN.md` | **GAP-5** (catalog fields / app overlay) |
| Library type tags, screenshots | `about` prose only | **GAP-6** |
| Android host (JNI/NDK bindings, AArch64 JIT on Android, Oboe audio) | Hosts exist for wasm, desktop (cpal) and plugin only | **GAP-7** (eng plan) |

---

## 3. Feature list and priorities

**MVP** = v1.0 launch; **1.1** = next release; **Later** = needs emulator work or more validation.

### 3.1 MVP (FM-1 only)

| ID | Feature | Trace | Gap / note |
|---|---|---|---|
| M1 | **Library**: FM-1 catalog, search, cards (name, version, author, about, licence, source/Hub links), download with progress, offline cache, update check, **reduced-clock** badge ("Plays at 160 MHz here") once Harbor has picked one | `web/firmwares.json`, `main.js` `packageOf`, "Check for firmware updates" | Catalog delivery (Q8) |
| M2 | **Open a local `.fwsc`** (picker + "Open with"), including the **bring-your-own stock firmware** flow | `byo: true` entry, `openLocal()` | Direct download of M-VAVE's file (Q8) |
| M3 | **Play screen**: LCD, 7 encoders, MASTER, 14 LED buttons, 27 keys; multi-touch, glissando; four adaptive layouts (§5) | `panel.rs`, `main.js` pointer model | none |
| M4 | **Audio and performance**: buffer mode (Low latency / Stable), clock (240 default; **automatic reduced clock** 200/160/120, remembered per firmware per device, with manual override), live load, dropout detection, one-time clock notice, performance banner for problems a lower clock can't fix, "Copy a report" | `main.js` perf monitor, `#perfwarn` | GAP-7 |
| M5 | **Saves and backup**: auto-persist flash per firmware build, save status, Save now, Export/Import `.fm1save` (web/plugin interop), saved versions, Reset saved flash | `persistence.js`, `savefile.rs`, `docs/wasm-persistence-plan.md` | none |
| M6 | **MIDI input**: USB MIDI devices (host mode), BLE MIDI (scan and pair), other apps' MIDI outputs, **USB Peripheral Port** (a computer DAW over the phone's USB cable, with a "not in MIDI mode" hint); per-device enable; per-device channel remap; activity indicator; Panic | `main.js` `midiMessage`, `periph.rs` UART | GAP-1 for overflow (degrades gracefully) |
| M7 | **Harbor as a MIDI device**: visible to other apps with **one input port** ("MIDI IN") that plays the emulated MIDI IN; keeps playing in the background while a client is connected | Android `MidiDeviceService` (new) | Background policy (Q5) |
| M8 | **DX7 bank import** (`.syx`, stock firmware only, slots 1–32) | `fm1_load_syx` | none |
| M9 | **Onboarding, Help (controls guide, firmware MIDI notes, "Play from a computer"), About and licences, non-affiliation disclaimer** | web guide | GAP-5 (v1 hard-codes the notes) |
| M10 | **Accessibility baseline and haptics**: semantics for every control, Hold assist, contrast-corrected tokens, haptic detents | n/a | GAP-4 disclosed |
| M11 | **Audio routing UX**: output route display, Bluetooth latency warning, headphones-unplugged pause, audio focus, output meter with clip indicator | n/a (host) | none |
| M12 | **"Makes no sound of its own" notice** for MIDI-controller firmwares (CTL-1) | README / catalog prose | GAP-5 |

### 3.2 v1.1

| ID | Feature | Trace / note |
|---|---|---|
| F1 | **SMK-37 board**: catalog (15 firmwares), panel (§6.10), velocity keys, RGB pads, faders, wheels, SMK MIDI mapping | `panel_smk.rs`, `smk.rs`, `web/smk37/` |
| F2 | **MIDI learn**: map external CC/notes to panel controls (encoders, MASTER, buttons) at app level; useful because most firmwares ignore CCs (UX reserved, §11.4) | host-side |
| F3 | **MIDI monitor**: last 200 inbound messages per source, with drop counter | GAP-1 |
| F4 | **Hardware keyboard mapping** (tablets with keyboards, Chromebooks, DeX), the web's map | `main.js` `PIANO`, `BUTTONS` |
| F5 | **Foldable postures**: tabletop (LCD and controls above the hinge, keybed below), book | Jetpack WindowManager |
| F6 | **Record output to WAV** and share; **LCD screenshot/GIF** share | app-side capture |
| F7 | **Favourites and recents**; app shortcuts ("Resume Felucca") | n/a |
| F8 | **Deep links** from `joakimeriksson.github.io/fm1-emu/?fw=<id>` and Hub ids | `main.js` `byId` |
| F9 | **Overclock** 320/400 MHz (plugin parity) for firmwares that run out of time | plugin "CPU clock" |
| F10 | Catalog metadata: type tags, screenshots, MIDI hints from upstream | GAP-5, GAP-6 |

### 3.3 Later

| ID | Feature | Blocked by |
|---|---|---|
| L1 | **Mic input** for sampler firmwares (April OS, zp12, PIGEON); would need RECORD_AUDIO, a feedback warning and an input meter | GAP-3 |
| L2 | Harbor as a **BLE MIDI peripheral** (input only: an iPad or laptop plays Harbor wirelessly) | no framework BLE-MIDI peripheral role; custom GATT server |
| L3 | **LCD readout for screen readers** (OCR or per-firmware hooks) | GAP-4, research |
| L4 | Inter-app audio (e.g. AAP) | ecosystem |
| L5 | Two emulator instances at once | CPU budget |

---

## 4. Information architecture and navigation

### 4.1 Screen list

| # | Screen | Type | Reached from |
|---|---|---|---|
| S0 | Onboarding (3 steps) | Full screen, first run only | Launch |
| S1 | **Play**: the home screen | Root | Launch (resumes last firmware) |
| S2 | **Library** | Destination | Firmware title on Play, rail |
| S2a | Firmware details | Bottom sheet (compact) / detail pane (expanded) | Library card, long-press title |
| S3 | **Saves & backup** (running firmware) | Destination | Save chip, rail, menu |
| S4 | **MIDI** | Destination | MIDI chip, rail, menu |
| S4a | Bluetooth MIDI scan | Modal bottom sheet | S4 |
| S4b | Play from a computer (USB) guide | Bottom sheet | S4 hint / Help |
| S4c | MIDI device detail (enable, channel, rename; MIDI learn in 1.1) | Sheet / detail pane | S4 row |
| S5 | **Audio & performance** | Destination | Load chip, perf banner, Settings |
| S6 | **Controls & display** | Destination | Settings |
| S7 | Help | Destination | Menu, onboarding |
| S8 | About & licences | Destination | Settings |
| — | Settings hub (S4–S8) | Destination | Menu / rail |

### 4.2 Navigation per window size class

| Width class | Primary nav | Play chrome |
|---|---|---|
| **Compact** (< 600 dp: phones) | No bottom bar (it would take keybed space). Play has a **top app bar** with the firmware title (tap: Library), status chips (Save, MIDI, Load) and an overflow menu (Library, Saves, MIDI, Audio, Settings, Help). Other screens are full-screen destinations with Up. | Portrait: 56 dp top bar. Landscape: a **floating 40 dp chrome pill** top-right. **Performance mode** (pin icon) hides all chrome; a two-finger swipe down from the top edge brings it back. |
| **Medium** (600–839 dp: tablet portrait, unfolded foldable) | **Navigation rail** (Play, Library, Saves, MIDI, Settings), hidden in performance mode | Header row of chips above the panel |
| **Expanded** (≥ 840 dp: tablet landscape, desktop windowing) | Rail plus a **supporting pane** on Play (320 dp, collapsible) with *Now docked / Your save / Play from* cards (the web page's sidebar). Library, Saves and MIDI use **list-detail**. | Header row |

Back: from any destination Back returns to Play. On Play while sound is running, the first Back shows "Press back again to leave Harbor" (2 s); this guards against accidental exits mid-performance.

### 4.3 Key flows

**F-A First run → first sound**
```
Launch ─► 1/3 "Welcome to Harbor" ─► 2/3 "Pick a firmware" (Felucca preselected; 4 suggestions; Browse all)
       ─► 3/3 "How to play" (drag knobs up/down · several fingers · firmware's own SAVE)
       ─► Play: "Bringing Felucca 1.6.1 into harbor…" (progress, Cancel) ─► Booting ─► Running (sound on)
```
No permission prompts on this path.

**F-B Switch firmware**
Title ▸ Library ▸ card **Play** ▸ (download if needed) ▸ running firmware's flash flushed ▸ reboot into the new one ▸ Play.
A one-time tip: *"Edits held only in the firmware's memory are lost when you switch. Use its own SAVE first."* (RAM-only edits can't be detected.)

**F-C Stock firmware (bring your own)**
Library ▸ "M-VAVE stock firmware" ▸ details: *"Harbor can't include M-VAVE's firmware. Download FM-1.fwsc from M-VAVE, then open it here."*
▸ [Download from M-VAVE ↗] (browser) ▸ [Open FM-1.fwsc…] (picker) ▸ validate ▸ boot ▸ tip: *"It starts with a small bank of the emulator's own. Load a DX7 bank (.syx) for 32 more voices."*

**F-D Back up and restore**
Save chip ▸ Saves ▸ **Export…** (system "Save to") or **Share…** ▸ `Felucca_1.6.1.fm1save`.
Restore: **Import…** or "Open with Harbor" from Files/Gmail/Drive ▸ summary (firmware, version, date, sectors) ▸ **Replace current save** ▸ reboot.
A stock backup without its package asks for the matching `FM-1.fwsc` first.

**F-E Played by another app on the phone**
DAW ▸ chooses MIDI output **"Harbor · MIDI IN"** ▸ Harbor's service starts (or is running) ▸ notification *"Harbor is playing Felucca for another app · Stop · Open"* ▸ plays in the background. MIDI chip shows "1 app connected".

**F-F Played by a computer DAW over USB**
Phone plugged into a laptop ▸ Harbor sees USB connected but no USB Peripheral Port ▸ MIDI chip shows a hint dot; MIDI screen shows the **"Set USB to MIDI"** card ▸ [Show me how] (S4b: steps) ▸ [Open settings] ▸ user picks *USB → MIDI* ▸ returns ▸ **"USB Peripheral Port"** appears, enabled ▸ laptop DAW picks the phone ("Android"/device name) as MIDI output ▸ plays. **Audio comes from the phone** (its speaker, headphones or audio interface), not over USB.

**F-G Heavy firmware**
Play ▸ the engine sees the firmware can't keep real time at 240 MHz ▸ snapshots flash and restarts it one clock step lower (240 → 200 → 160 → 120) until it keeps up ▸ one-time snackbar *"Running X0X at 160 MHz so it plays smoothly on this phone."* [Details] ▸ next launch starts at 160 with no stutter. The Library card then shows "Plays at 160 MHz here". If dropouts continue for another reason, the perf banner (§10.3).

---

## 5. Adaptive layout system

### 5.1 Principles

1. **Re-flow, don't shrink.** The faithful hardware arrangement only where it fits with usable targets; elsewhere the
   same controls are rearranged into zones that keep the hardware's grouping (left encoders, centre LCD, right knobs
   and buttons, bottom keys).
2. **The LCD is sacred.** Always visible, square, near-integer scale of 240 px, never cropped.
3. **Every FM-1 control is always on screen**: no menus, tabs or scrolling to reach a control, in every layout.
4. **Configuration changes never reboot the emulator.** Rotation, fold/unfold, split-screen and theme changes keep the
   machine running with no audio gap over 50 ms. Held keys are released cleanly.
5. Layout uses **width and height** classes (phone landscape is *compact height*, not medium width).

### 5.2 Layout modes

| Mode | Condition | Typical device | FM-1 layout |
|---|---|---|---|
| **C-P** compact portrait | width < 600 dp, height ≥ 480 dp | phone portrait | Re-flowed stack; **two-tier keybed** shows all 27 keys |
| **C-L** compact landscape | height < 480 dp | phone landscape | Hardware arrangement in one band + full-width keybed |
| **M** medium | width 600–839 dp, height ≥ 480 dp | tablet portrait, unfolded foldable | Faithful upper panel at full width + **decoupled, taller keybed** + cards below |
| **E** expanded | width ≥ 840 dp, height ≥ 480 dp | tablet landscape, desktop windowing | **Fully faithful panel** + supporting pane + rail |

Reference sizes: phones 360×780 dp (e.g. Galaxy S23) and 412×915 dp (e.g. Pixel 8 Pro); tablet 800×1280 / 1280×800 dp; foldable inner 673×841 dp.

### 5.3 Size budgets (minimums)

| Control | Min visual | Min touch target | Notes |
|---|---|---|---|
| Encoder / MASTER | 52 dp diameter | 60 dp (hit radius = visual + 8 dp, like `panel.rs` +6 px) | 64 dp preferred on phones |
| Panel button | 44×36 dp | 48×44 dp | LED dot inside the cap |
| White key | 40 dp × 120 dp | same | **Documented exception** to 48 dp: keys are one continuous adjacent surface (glissando), as in other piano apps |
| Black key | 26 dp × 60% of white height | same | Black keys hit-tested first (as `panel.rs`) |
| LCD | 168 dp (C-P), 176 dp (C-L), 288 dp+ (M/E) | not interactive | Prefer 1×/1.5×/2× device-pixel multiples |

---

## 6. Screen specs

Wireframe notation: `( )` encoder or pot, `[ ]` button (`•` = LED), `▓` LCD, keys drawn with `|` and `█`.

### 6.0 Play chrome components

| Component | Content | Behaviour |
|---|---|---|
| **Firmware title** | "Felucca 1.6.1" ▾ | Tap: Library. Long-press: Firmware details. |
| **Save chip** | ✓ "Saved" / ⟳ "Saving…" / ⚠ "Save failed" / ○ "Not saved yet" | Tap: Saves. Failure turns it amber, and Saves offers "Export a copy". |
| **MIDI chip** | DIN glyph + activity dot (flash 120 ms per message, ignoring real-time bytes ≥ 0xF8, web parity) + count of active sources; a small hint dot when the USB-mode hint applies | Tap: MIDI |
| **Load chip** | "240 MHz · 42%": amber over 80%, red while dropping | Tap: Audio & performance |
| **Perf banner** | M3 banner under the bar (§10.3) | Dismissible; returns only if the condition persists 10 s |
| **LCD overlay** | Downloading / Booting / Halted / Paused / Needs file | See states |
| **Overflow menu** | Library · Saves & backup · MIDI · Audio & performance · Controls & display · Help · About · Performance mode | n/a |

### 6.1 S0 Onboarding

```
C-P                                     E: centred 560 dp card, illustration left
┌──────────────────────────────┐
│            1 / 3              │
│   [ panel illustration ]      │
│                               │
│  Welcome to Harbor            │
│  Plays M-VAVE FM-1 firmwares  │
│  on your phone or tablet,     │
│  unchanged: Felucca, SLOOP,   │
│  Skiff and four dozen more.   │
│  Not made by or affiliated    │
│  with M-VAVE.                 │
│   ● ○ ○          [ Next ]     │
└──────────────────────────────┘
2/3 "Pick a firmware to start": 4 suggestion cards (Felucca default, X0X, OberVave, AMB-1) · "Browse all 47"
3/3 "How to play": "Drag a knob up or down" · "Use several fingers: chords, or hold a key and turn a knob" ·
    "Patches live in the firmware. Use its SAVE button and Harbor keeps what it saves."  [ Start playing ]
```
- Skip on every step goes to Play with Felucca.
- **No permission prompts.** Nearby devices (BLE) and notifications are asked in context.
- Offline at step 2: suggestion cards show "Needs internet to download" with [Open a .fwsc file instead].

### 6.2 S1 Play

#### 6.2.1 C-P: phone portrait

```
┌────────────────────────────────────────┐
│ Felucca 1.6.1 ▾   ✓Saved  ⦿MIDI  42% ⋮ │ 56 dp top bar
├────────────────────────────────────────┤
│  (MASTER)  (SELECT)   ┌────────────┐   │
│                       │            │   │
│  (PRESETS) (ALGO)     │  ▓▓ LCD ▓▓ │   │ LCD 192 dp (0.8×)
│                       │  240×240   │   │
│  [OCT−•]  [OCT+•]     └────────────┘   │
├────────────────────────────────────────┤
│   (KNOB1)  (KNOB2)  (KNOB3)  (KNOB4)   │ 64 dp knobs
│ [FX•][SCL•][ENV•][LFO•][EDIT•][GLO•]   │ 2×6 buttons
│ [HOME•][SAVE•][ARP•][SEQ•][PLAY•][REC•]│ ~58×48 dp
├────────────────────────────────────────┤
│ upper tier  F4 ────────────────── G5   │
│ | |█| |█| |█| | |█| |█| | |█| |█| |█| ││ 9 white keys, ~42 dp
│ | | | | | | | | | | | | | | | | | | | ││
├╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌╌┤
│ lower tier  F3 ─────────── E4          │
│ |  |██|  |██|  |██|  |  |██|  |██|  |  ││ 7 white keys, ~54 dp
│ |  |  |  |  |  |  |  |  |  |  |  |  |  ││
└────────────────────────────────────────┘
```
- **Two-tier keybed** (default): lower F3–E4 (12 keys), upper F4–G5 (15 keys), higher pitch on top as on a two-manual organ.
  All 27 matrix keys are visible at once, so firmwares that put functions on specific keys stay usable.
- **Single row, windowed** (setting): 8 white keys (about 48 dp) with a 16 dp **minimap** showing all 27 keys and the visible window;
  drag the minimap or use the ‹ › edge tabs. **The window is a view scroll, not the firmware's OCT−/OCT+.** The minimap is styled
  differently (outline only) so the two never get confused.
- Height budget (about 840 dp usable on 412×915): bar 56 + LCD block 216 + knobs 80 + buttons 112 + keybed 376 (two tiers of 188).
  On 360×780 dp: LCD 168, knobs 56, tiers 160 each.

#### 6.2.2 C-L: phone landscape

```
┌───────────────────────────────────────────────────────────────────────────────────────┐
│(MASTER)(SELECT)   ┌──────────┐  (KNOB1) (KNOB2) (KNOB3) (KNOB4)            ┌─────────┐ │
│                   │          │                                             │Felucca ▾│ │ floating chrome pill
│(PRESETS)(ALGO)    │ ▓ LCD ▓  │  [FX•][SCL•][ENV•][LFO•][EDIT•][GLO•]       │✓ ⦿ 42% ⋮│ │ (hidden in
│                   │  176 dp  │  [HOME•][SAVE•][ARP•][SEQ•][PLAY•][REC•]    └─────────┘ │  performance mode)
│[OCT−•][OCT+•]     └──────────┘                                                         │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ | |█| |█| |█| | |█| |█| | |█| |█| |█| | |█| |█| | |█| |█| |█| | |█| |█| | |█| |█| |  │ 27 keys F3–G5
│ | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | | |  │ 16 white × ~55 dp
└───────────────────────────────────────────────────────────────────────────────────────┘
```
- Almost one to one with the real panel, so landscape is the suggested phone orientation (one-time hint in C-P:
  "Turn sideways for the full panel"). C-P stays fully functional.
- Immersive mode on by default. The keybed and edge encoders register **system gesture exclusion rects** (Android
  allows 200 dp per edge; the keybed has priority) so they don't trigger Back.
- Height budget (412 dp): upper band 212 (LCD 176 + padding), keybed 184, insets 16.

#### 6.2.3 M: tablet portrait / unfolded foldable

```
┌──────┬───────────────────────────────────────────────────────────────┐
│ Rail │ Felucca 1.6.1 ▾                ✓ Saved   ⦿ MIDI 1   42%      │
│ ▶Play│ ┌───────────────────────────────────────────────────────────┐ │
│ Lib  │ │ (MST) (SEL)       ┌────────────────┐  (K1) (K2) (K3) (K4)  │ │ faithful upper panel
│ Save │ │                   │                │  [FX][SCL][ENV][LFO]  │ │ (panel.rs grouping,
│ MIDI │ │ (PRE) (ALG)       │   ▓ LCD 288 ▓  │  [EDIT][GLO][HOME]    │ │  fitted to width)
│ Set  │ │                   │                │  [SAVE][ARP][SEQ]     │ │
│      │ │ [OCT−] [OCT+]     └────────────────┘  [PLAY][REC]          │ │
│      │ └───────────────────────────────────────────────────────────┘ │
│      │ ┌───────────────────────────────────────────────────────────┐ │
│      │ │ 27-key keybed, 16 whites × ~44 dp, 220 dp tall (decoupled) │ │
│      │ └───────────────────────────────────────────────────────────┘ │
│      │ ┌──────────── Now docked ────────┐┌──── Your save ──────────┐ │
│      │ │ Felucca 1.6.1 · hugelton · GPL ││ ✓ Saved 12:03           │ │
│      │ │ Clock 240 MHz ▾  [Details]     ││ [Save now] [Export…]    │ │
│      │ └────────────────────────────────┘└─────────────────────────┘ │
└──────┴───────────────────────────────────────────────────────────────┘
```
- The keybed is **decoupled** from the panel scale. At panel scale the keys would be only about 60 dp tall here.

#### 6.2.4 E: tablet landscape

```
┌──────┬───────────────────────────────────────────────────────────┬───────────────────┐
│ Rail │ Felucca 1.6.1 ▾                    ✓ Saved  ⦿ MIDI  42%   │ ‹ hide pane       │
│      │ ┌───────────────────────────────────────────────────────┐ │ NOW DOCKED        │
│      │ │ (MST)(SEL)      ┌──────────────┐ (K1)(K2)(K3)(K4)     │ │ Felucca 1.6.1     │
│      │ │                 │              │ [FX][SCL][ENV][LFO]  │ │ The original open │
│      │ │ (PRE)(ALG)      │  ▓ LCD 2× ▓  │ [EDIT][GLO]          │ │ firmware…         │
│      │ │                 │  (480 px)    │ [HOME][SAVE][ARP]    │ │ Clock [240 MHz ▾] │
│      │ │ [OCT−][OCT+]    └──────────────┘ [SEQ][PLAY][REC]     │ │ [Open .fwsc…]     │
│      │ │ ───────────────────────────────────────────────────── │ │ YOUR SAVE         │
│      │ │ | |█| |█| |█| | |█| |█| | |█| |█| |█| | |█| |█| | |█| │ │ ✓ Saved 12:03     │
│      │ │ 27 keys, hardware proportions                          │ │ [Save][Export…]   │
│      │ └───────────────────────────────────────────────────────┘ │ PLAY FROM         │
│      │                                                           │ ⦿ KeyStep 37      │
│      │                                                           │ ⦿ USB Peripheral  │
└──────┴───────────────────────────────────────────────────────────┴───────────────────┘
```
- **Fully faithful** `panel.rs` geometry (1280×820 units), scaled uniformly. LCD at 2× (480 px) when space allows.
- The pane collapses to the rail (remembered), and the panel grows.

#### 6.2.5 Play states

| State | Trigger | UI |
|---|---|---|
| **Empty harbor** | Onboarding skipped while offline, or the package was removed | Panel greyed; LCD placeholder "Nothing docked yet" with [Browse firmwares] [Open a .fwsc file] |
| **Downloading** | First play of a firmware | LCD overlay: "Bringing Felucca into harbor…", progress %, size, [Cancel]. Panel inert. |
| **Download failed** | Network error, 404, bad package | ⚠ "Couldn't download Felucca from thefirmwarehub.com" [Retry] [Open a file instead] |
| **Needs file** | Stock firmware chosen, no kept package | "Bring your own FM-1.fwsc" [Download from M-VAVE ↗] [Open FM-1.fwsc…] |
| **Booting** | Package ready | LCD shows the firmware's own boot frames as they come; chip "Booting…" |
| **Running** | normal | n/a |
| **Clock reduced** | The engine stepped the clock down (or starts at a remembered reduced clock) | Load chip reads "160 MHz · 71%". The first time per firmware: snackbar "Running X0X at 160 MHz so it plays smoothly on this phone." [Details → S5] |
| **No sound of its own** | Catalog flag (CTL-1) | Info banner: "CTL-1 is a MIDI controller: it makes no sound of its own in Harbor." |
| **CPU halted** | `fm1_halted()` | Frozen LCD + "The emulated CPU halted" [Restart firmware] [Copy a report] |
| **Can't keep up** | perf monitor | Perf banner (§10.3) |
| **Paused** | Headphones unplugged, permanent focus loss, user pause | Dim scrim: "Paused · Headphones disconnected" [Resume] |
| **No audio output** | Stream can't open or re-route | Banner "No audio output available" [Retry] [Audio settings]. The emulator keeps the LCD live. |
| **Import / boot error** | Invalid `.fwsc`/`.fm1save`, wrong board (SMK-37 package in v1) | Dialog with reason ("This is an SMK-37 firmware. SMK-37 support is coming in a later version."). The current setup is left intact. |

### 6.3 S2 Library

```
C-P                                         E (list-detail)
┌──────────────────────────────────────┐   ┌──────┬─────────────────────┬──────────────────────┐
│ ← Firmwares               🔍   ⋮     │   │ Rail │ Firmwares (49)   🔍  │  Felucca 1.6.1       │
│ 49 firmwares for the FM-1            │   │      │ ▸ Felucca   ● Docked │  by hugelton · GPL-3 │
│ Recently played: Felucca · X0X       │   │      │ ▸ X0X  ↓ · 160 MHz   │ The original open…  │
│ ┌──────────────────────────────────┐ │   │      │ ▸ OberVave           │  MIDI: parts ch 1–4  │
│ │ Felucca 1.6.1         ● Docked   │ │   │      │ ▸ M-VAVE stock  BYO  │  [Play] [Source ↗]   │
│ │ hugelton · GPL-3.0    ↓ 412 KB   │ │   │      │ …                    │  [On the Hub ↗]      │
│ │ The original open firmware: a    │ │   │      │                      │  Saved versions (2)  │
│ │ four-operator FM synth with …    │ │   │      │                      │  [Check for updates] │
│ │ [ Play ]   Source ↗   Hub ↗      │ │   │      │                      │  [Remove download]   │
│ └──────────────────────────────────┘ │   └──────┴─────────────────────┴──────────────────────┘
│ ┌──────────────────────────────────┐ │
│ │ X0X 1.1.0  ↓ cached · 160 MHz here│ │
│ │ charlesvestal · MIT               │ │
│ │ [ Play ]                          │ │
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ M-VAVE stock firmware      BYO   │ │
│ │ [ Open FM-1.fwsc… ]  Download ↗  │ │
│ └──────────────────────────────────┘ │
│              [ Open a .fwsc file… ]  │ extended FAB
└──────────────────────────────────────┘
```
- **Card fields** (all in the catalog): `name`, `version`, `author`, `license`, `about`, `repo` (Source ↗), `hub` (On the Hub ↗), `installer` (Install on a real FM-1 ↗, opens the browser).
- **Badges**: ● Docked (running) · ↓ Cached (size) · ⟳ Update available · BYO · ⚠ Download failed · Saved (has flash changes) · **"160 MHz here"** (neutral, informational: the clock Harbor picked on this device).
- Overflow: Sort (catalog / A–Z / recently played) · Refresh list · Manage downloads (storage, clear).
- Search over name, author and about text.
- **Details** (sheet / pane): full `about`; licence with what it means for sharing ("A shared save includes the firmware, so its licence applies");
  MIDI notes (v1 hard-coded from the web guide, GAP-5); clock on this device ("Plays at 160 MHz on this phone", with [Change clock] → S5); saved versions; Check for updates; Remove download; Reset saved flash.
- **States**: loading (skeleton cards); offline (cached shown, others "Needs internet", Play disabled); list refresh failed (bundled snapshot, subtle "List from 12 Oct"); empty search ("No firmware by that name in the harbor").

### 6.4 S3 Saves & backup

Model (fm1-emu): each **exact package build** (SHA-256) has its own save of changed 4 KiB flash sectors. What the firmware
writes to flash is saved automatically; RAM-only edits are not. Updates get a separate save and are never migrated.

```
┌──────────────────────────────────────┐
│ ← Saves & backup                     │
│ Felucca 1.6.1 · package a3f9…        │
│ ┌──────────────────────────────────┐ │
│ │ ✓ Saved automatically · 12:03    │ │ live status (polite announcement)
│ │ 14 flash sectors changed         │ │
│ │ [ Save now ]                     │ │
│ └──────────────────────────────────┘ │
│ ⓘ Patch edits live in the firmware's │
│   memory until you use its own SAVE. │
│ BACKUP                               │
│ [ Export .fm1save… ]  [ Share… ]     │
│ [ Import .fm1save… ]                 │
│ STOCK FIRMWARE ONLY                  │
│ [ Load DX7 bank (.syx)… ]  E.PIANO.syx│
│ VERSIONS OF THIS FIRMWARE            │
│  ● 1.6.1 (a3f9…) current · 12:03     │
│  ○ 1.6.0 (77c1…) · 3 Oct  [Open]     │
│ [ Check for firmware updates ]       │
│ DANGER ZONE                          │
│ [ Reset saved flash… ]               │
└──────────────────────────────────────┘
```
- **Export**: `<Firmware>_<version>.fm1save`. It waits for in-progress flash writes ("Finishing a flash write…") and writes in the background.
  For stock firmware it notes that the recipient needs their own `FM-1.fwsc`.
- **Import**: summary first (firmware, version, board, date, sectors). A bad board, corrupt file or unknown version gives an error with the current setup untouched.
- **Reset**: confirmation naming the firmware; destructive button "Reset and restart".
- **Save failed**: ⚠ "Your changes are kept in memory. Export a copy now."
- **States**: nothing running (links to Library); BYO package missing (import waits for the package); "Not saved yet: nothing written to flash".

### 6.5 S4 MIDI

```
C-P                                              E: list-detail (sources | device detail)
┌──────────────────────────────────────────┐
│ ← MIDI                                   │
│ PLAY FROM  (into the FM-1's MIDI IN)      │
│ ┌──────────────────────────────────────┐ │
│ │ ⦿ Arturia KeyStep 37   USB    [on ]  │ │ activity dot · type · enable
│ │   Channel: as sent ▾                 │ │
│ ├──────────────────────────────────────┤ │
│ │ ○ WIDI Master   Bluetooth  [on ]  ⋮  │ │ ⋮ Forget / Reconnect
│ │   Connected · Channel: 2 ▾           │ │
│ ├──────────────────────────────────────┤ │
│ │ ○ USB Peripheral Port  Computer [on ]│ │ the phone as a USB MIDI gadget
│ │   Channel: as sent ▾                 │ │
│ ├──────────────────────────────────────┤ │
│ │ ○ FL Studio Mobile   App   [off]     │ │ other apps' MIDI outputs
│ └──────────────────────────────────────┘ │
│ [ + Pair Bluetooth MIDI device ]          │
│ New devices: enable automatically  [on ]  │
│ [ Panic: all notes off ]                  │
│                                          │
│ HARBOR AS A MIDI DEVICE                   │
│ ┌──────────────────────────────────────┐ │
│ │ Visible to other apps         [on ]  │ │
│ │ "Harbor" · input "MIDI IN"            │ │
│ │ 1 app connected  ⦿                    │ │
│ │ Keep playing in background    [on ]  │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ FOR THIS FIRMWARE (Felucca)               │
│ Parts play on channels 1–4.               │
│ [ MIDI learn… ] (1.1)                     │
└──────────────────────────────────────────┘
```
- **Merge**: every enabled source feeds the emulated MIDI IN.
- **Channel remap** per source: "As sent" (default) or 1–16, which moves all channel messages to that channel (web parity, made per source).
  A **firmware hint** offers the right value: stock: *"Listens on channel 2 (seen in the emulator; unverified on hardware)"* [Use channel 2];
  Felucca family: *"Parts on channels 1–4"*; ChoralRoot, Hortator, SLOOP ALG, X0X: *"Makes no sound from a plain note-on"*.
- **Activity dot** per source: 120 ms flash per non-real-time message; clock-only traffic gives a slow "clock" pulse.
- **Panic** sends all-notes-off / releases held notes. It also fires automatically when a source disconnects mid-note.
- **States**:
  - No sources: "No MIDI devices. Plug in a USB keyboard, pair a Bluetooth one, or connect a computer."
  - USB device attached: system permission prompt; row "Tap to allow" until granted.
  - Nearby devices denied: inline explanation + [Open settings]; USB, app and computer sources keep working.
  - Bluetooth off: [Turn on Bluetooth].
  - Device gone: row greyed "Disconnected · reconnects automatically" (BLE: retries for 30 s, then manual).
  - Overflow (when GAP-1 is exported): "⚠ 120 bytes dropped: the FM-1's MIDI IN runs at 31,250 baud, like the real jack" (once, with Learn more).
  - **USB connected but not in MIDI mode** (phone charging from a computer, no USB Peripheral Port): card at the top of PLAY FROM, see S4b.

**S4a Bluetooth MIDI scan (bottom sheet)**
```
┌──────────────────────────────────────┐
│ Pair a Bluetooth MIDI device          │
│ ⟳ Scanning… (stops after 20 s)        │
│  WIDI Master            ▂▄▆  [Connect]│
│  CME Xkey Air           ▂▄   [Connect]│
│ Not listed? Put it in pairing mode,   │
│ and disconnect it from other apps.    │
│                    [ Stop ] [ Done ]  │
└──────────────────────────────────────┘
```
- Asks for **Nearby devices** (`BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_CONNECT`) the first time this sheet opens, after a one-line rationale. No location permission (minSdk 31).
- Filters to the BLE-MIDI service. Connect: "Connecting…" (10 s timeout, Retry).

**S4b Play from a computer (USB)**
Shown as the hint card and as a sheet from Help.
```
┌──────────────────────────────────────────┐
│ ⓘ Connected to a computer by USB          │
│ To play Harbor from a DAW on the computer,│
│ set this phone's USB mode to MIDI.        │
│ [ Show me how ]  [ Open settings ]   ✕    │
└──────────────────────────────────────────┘
Sheet "Play Harbor from a computer":
 1. Keep the phone plugged into the computer.
 2. Open the "Charging this device via USB" notification, or Settings ▸ Connected devices ▸ USB.
 3. Choose "MIDI".
 4. Back in Harbor, "USB Peripheral Port" appears under Play from.
 5. In your DAW, pick this phone as the MIDI output for a track.
 ⓘ Sound still plays from the phone: its speaker, headphones or audio interface,
   not over the USB cable. Connect the phone's output to your mixer to hear it with the DAW.
 ⓘ While USB is in MIDI mode the phone can't use a USB MIDI keyboard on the same port.
                                   [ Open settings ]  [ Done ]
```
- The hint shows when USB is connected to a computer and no USB Peripheral Port exists; dismiss (✕) hides it until the next plug-in.
- When the port appears: snackbar "Computer connected over USB" and the row auto-enables (per "enable automatically").
- [Open settings] goes as deep as the platform allows (Q11); the steps cover the rest.

### 6.6 S5 Audio & performance

```
┌──────────────────────────────────────────┐
│ ← Audio & performance                    │
│ OUTPUT                                   │
│ 🔈 Phone speaker · 48 kHz · 192 frames    │
│ Estimated output latency ≈ 21 ms          │
│ L ▕██████████▁▁▁▁▁▏ −9 dB                 │
│ R ▕██████████▁▁▁▁▁▏ −9 dB   [CLIP]        │ clip holds 2 s
│ Audio buffer   (•) Low latency ( ) Stable │
│ "Stable adds a little delay to the keys   │
│  but rides out a busy device."            │
│ EMULATION                                │
│ Clock  [ 240 MHz ▾ ]  (240 · 200 · 160 ·  │
│        120 "reduced, for heavy firmwares")│
│ Load ▕██████▁▁▁▁▏ 42% of the audio time   │
│ Seconds with dropouts: 0                  │
│ [ Copy a report ]                         │
│ BACKGROUND                               │
│ Keep playing in background    [off]       │
│ Keep screen on while playing   [on ]      │
│ WARNINGS                                 │
│ Bluetooth latency warning      [on ]      │
└──────────────────────────────────────────┘
```
- The clock is **automatic by default** and **remembered per firmware per device** (so X0X can sit at 160 while Felucca runs at 240).
  The picker shows "Automatic (160 MHz)" plus fixed choices; picking one turns automatic off for that firmware, and
  "Reset to automatic" turns it back on. Copy under the picker: "A lower clock gives the firmware less time to compute,
  so under heavy load it may drop voices or effects."
- The engine only ever steps the clock **down** on its own. It never raises it; resetting to automatic starts again at 240.
- Changing clock or buffer restarts the firmware: snackbar "Restarted at 160 MHz. Saved flash kept."
- "Copy a report": device, Android version, route/rate/buffer, firmware, clock, 30 s load history, dropouts, JIT stats (web parity).
- **No app volume slider**: MASTER is the volume; the hardware keys set media volume.

### 6.7 S6 Controls & display

| Setting | Options (default **bold**) |
|---|---|
| Knob gesture | **Vertical drag** · Circular · Both |
| Knob sensitivity | Fine (18 dp/detent) · **Normal (12 dp)** · Fast (8 dp) |
| Tap knob to step | **On** (top/right half +1, bottom/left −1) · Off |
| Keybed in phone portrait | **Two tiers** · Single row (scroll) |
| Haptics | **Knob detents** ☑ · **Buttons** ☑ · Keys ☐ |
| Hold assist (accessibility) | **Off** · On: a tap latches a button down |
| Palm rejection (tablets) | **On** · Off |
| LCD scaling | **Pixel-exact** · Smooth |
| Panel look | **Hardware** · Flat high-contrast |
| Note names on keys | **Off** · C only · All |
| Immersive in landscape | **On** · Off |
| SMK-37 rows (velocity, pads) | v1.1 |

### 6.8 S7 Help

- **Controls guide**: labelled panel diagram; gestures; "The firmware decides what each button does."
- **Firmware notes**: catalog prose plus MIDI hints.
- **Play from a computer (USB)**: S4b content.
- **Known limits**: no MIDI out; USB and Bluetooth features inside firmwares don't work; no mic input; M-VAVE's factory patch bank isn't included; some firmwares ignore plain note-ons; the LCD can't be read by TalkBack.

### 6.9 S8 About & licences

- Harbor version; emulator core version (fm1-emu commit); "Harbor plays M-VAVE FM-1 firmwares. Not made by or affiliated with M-VAVE."
- Open-source licences, including fm1-emu (MIT).
- **Firmware licences**: per downloaded firmware, its licence and repo; "Harbor hosts no firmware."
- Credits to the RE and firmware community (FM-1-RE, lunar-modulator, jielie, Felucca, the Firmware Hub), as in the fm1-emu README.

### 6.10 SMK-37 panel (v1.1)

The SMK-37 (1920×880 units, 2.18:1) has 2.2× the FM-1's controls. Fully faithful only in **E**; elsewhere a
**zone switcher** holds the control groups. (This is the one exception to principle 5.1.3, accepted for v1.1.)

| Mode | Layout |
|---|---|
| **C-P** | Top bar · left six buttons (KNOB BANK, FADER BANK, <, >, OCTAVE−/+) beside a 168 dp LCD · zone switcher **[Pads] [Knobs & faders] [Buttons]** (selection remembered per firmware): Pads = 4×4 grid (rows 1–2 = hardware top row 0–7, rows 3–4 = bottom row 8–15), 72 dp; Knobs = K1–K8 in 2×4 with column numbers + 4 horizontal faders; Buttons = 2×6 grid · pitch and mod wheels left of a **windowed keybed** (8 whites, minimap of 37 keys). |
| **C-L** | LCD 152 dp + six buttons left · zone strip right (Pads in the hardware 2×8 at 44 dp; Knobs = 8 in a row + faders) · wheels + windowed keybed (15 whites ≈ 52 dp, C3–C5 default). |
| **M** | Upper deck: wheels, buttons, LCD, K1–K8 over 2×8 pads, faders, right buttons (faithful arrangement fitted to width) · keybed deck: **two tiers** C3–B4 / C5–C6 (whites ≥ 48 dp), or windowed. |
| **E** | Fully faithful `panel_smk.rs` geometry. Supporting pane auto-collapses below 1,200 dp of panel width. |

SMK-specific interactions (v1.1): velocity from touch position on the key (top = soft, bottom = hard → contact time
80 → 2 ms, web `setVelocity`; option: fixed); pads press at a fixed medium value (web: 700/1023; option: position-based)
and release to 0, with an RGB glow from `fm1_pads`, and sliding off a pad releases it without triggering its neighbour;
faders absolute with relative pickup (no jump); pitch wheel springs back to rest on release, mod wheel stays. MIDI: no
jack, so all sources map notes C3–C6 → keys (velocity), pitch bend → pitch wheel, CC1 → mod wheel; channel remap is hidden.
Stock SMK-37 firmware gets the "makes no sound of its own" notice (it's a MIDI controller).

---

## 7. Touch interaction details for the panel controls

### 7.1 Encoders (SELECT, PRESETS, ALGORITHM, KNOB1–4)

Endless, detented, **relative**. The firmware gets clicks; the value lives on the LCD.

| Gesture | Result |
|---|---|
| **Vertical drag** (default) | Up = clockwise (+1). One detent per 12 dp (setting 8/12/18), matching the web's 12 px/click. Starts after touch slop; horizontal motion ignored. |
| **Circular drag** (setting) | Angle around the centre, one detent per 15° (24/rev). Best on large knobs (M/E). "Both": the first 16 dp decides; tangential motion means circular. |
| **Tap** | ±1 by half (top/right +1, bottom/left −1): a precise step for menus. < 250 ms, within slop. |
| **Fling** | **No momentum.** Lifting stops (menus must never overshoot). |
| **Double-tap** | **No reset.** An endless encoder has no default; two taps = two steps. |
| **Long-press** | Nothing (encoder push isn't modelled). **Never a context menu on the panel.** |
| **Fine mode** | While dragging, a second finger on empty panel background doubles distance per detent. |
| **Multi-touch** | Every pointer is independent (web parity): hold keys while turning, two knobs at once. |
| **Mouse wheel / trackpad** | One detent per notch (web: 40 delta units). |

Feedback: the cap rotates 15° per detent (cosmetic; no value scale is printed, because none exists); the ring glows amber for 160 ms after each detent (web "turning").

### 7.2 MASTER pot

Absolute 0–1023, 270° visual travel with a pointer. Vertical drag, full travel = 200 dp; fine mode ×0.25 with a second finger.
Tooltip "MASTER 62%" while dragging. **No double-tap reset** (no sudden volume jumps). Position **remembered per firmware**; the first boot of each firmware starts at 50%.
End-stop haptics at 0% and 100%.

### 7.3 Keys

- Down = key down; up/cancel = key up. Holds are reference-counted per key (web `holds[]`), so two fingers on one key are one press.
- **Glissando**: a pointer that starts on a key slides across keys, re-pressing each. A pointer that starts elsewhere never plays keys.
- Crossing between tiers continues the glissando (release + press).
- Up to 10 pointers; **palm rejection** on tablets (large contact area ignored).
- Pressed keys darken within one frame of touch-down, before audio.
- No key haptics by default.
- No velocity (the FM-1 has none).

### 7.4 Buttons and LEDs

- **Momentary**, exact timing; the firmware owns long-press and combo semantics, so Harbor never intercepts a long-press.
- **LEDs** from `fm1_leds` (0..1), at the LCD refresh rate (≥ 30 Hz). PWM dimming and blinking show as intermediate brightness. Colour amber (green where `panel.rs` uses it).
- **Hold assist** (accessibility): a tap latches the button down (ring + lock glyph); the next tap releases it. This enables one-finger combos.

### 7.5 Gesture conflict rules

- No scroll containers in the panel (except the windowed keybed's minimap, which owns its strip).
- System gesture exclusion on keybed and edge controls (200 dp/edge cap; keybed first).
- Immersive C-L: the chrome pill is reached by tap; swipe from top only reveals system bars.
- Touches that start on chrome never reach the panel.
- Double-Back to leave Play while sound runs (§4.2).

### 7.6 Haptics

| Event | Constant | Default |
|---|---|---|
| Encoder detent | `CLOCK_TICK` (≤ 1 per 20 ms, coalesced) | On |
| MASTER end stop | `CONTEXT_CLICK` | On |
| Button press / release | `VIRTUAL_KEY` / `VIRTUAL_KEY_RELEASE` | On |
| Key press | `KEYBOARD_TAP` | Off |
| Destructive confirm | `REJECT` | n/a |

Respects the system touch-feedback setting and the app toggles.

---

## 8. Accessibility

| Area | Requirement |
|---|---|
| **Semantics** | Every control is its own Compose semantics node (not one canvas). Labels from the silkscreen: "SELECT encoder", "KNOB 3 encoder", "MASTER", "FX button", "Key F sharp 3". |
| **Encoders** | Adjustable: TalkBack swipe up/down = ±1 detent; custom actions "Turn right", "Turn left", "Turn right 5". No value announced (none exists); hint "Value shown on the firmware screen." |
| **MASTER** | Slider, `ProgressBarRangeInfo` 0–100, state "62 percent". |
| **Keys** | Role button; double-tap = press + release (100 ms); double-tap-and-hold = hold. Group "Keyboard, 27 keys, F3 to G5". |
| **Buttons with LEDs** | State "light on" / "off" / "blinking" (threshold 0.5; blinking = ≥ 2 transitions/s). Not auto-announced. |
| **LCD** | "Firmware screen". **GAP-4:** its contents can't be read aloud in v1; Help says so plainly. |
| **Hold assist** | §7.4; also serves switch-access users. |
| **Targets** | Chrome ≥ 48 dp; panel per §5.3 with the documented keybed exception. |
| **Contrast** | Chrome text ≥ 4.5:1, large text and icons ≥ 3:1. Panel silkscreen ≥ 4.5:1 (§9.2 corrects the web's failing label colours). |
| **Font scaling** | Chrome to 200% (reflow, chips wrap). Panel labels scale to 130%, then use short forms (ALGO, PRST); TalkBack always gets full names. |
| **Colour independence** | Save chip uses icon + text; activity dot pulses; "CLIP" in words; reduced clock shown as text ("160 MHz"), not only colour. |
| **Reduce motion** | Respects "Remove animations": no knob easing, no glow bloom. LEDs still show state. |
| **Keyboard / switch** | Focus order LCD → left encoders → OCT → knobs → buttons → keys (low to high); 2 dp amber focus ring; arrows turn a focused encoder; Space/Enter press. |

---

## 9. Visual design direction

### 9.1 Direction

- **Dark by default** (the hardware is dark, and it suits stage use). Chrome follows the system light theme if set; the **panel always renders in hardware colours**.
- **Faithful, not photographic**: the same layout logic, proportions in M/E, silkscreen labels, body and key colours and lit
  LEDs, as fm1-emu's own vector panels draw them. Subtle gradients and soft shadows; no photo textures, screws or wear.
  **No M-VAVE logo or wordmark** anywhere on the panel or in the icon.
- **Harbor brand**: light nautical flavour in copy only ("Now docked", "Nothing docked yet", "Bringing X into harbor…"). Keep it to
  a few touches; functional text stays plain.
- **App icon direction**: a small sail or hull silhouette riding a single sine wave (echoes fm1-emu's sine logo), amber on #18191B. No keyboard, no M-VAVE marks. Monochrome themed-icon variant.
- **Dynamic colour**: off for the brand palette; optional for chrome.

### 9.2 Palette tokens

From fm1-emu (`web/style.css`, `panel.rs`, `panel_smk.rs`), corrected for contrast.

| Token | Value | Use | Contrast |
|---|---|---|---|
| `color.room` | #18191B | `background` / `surface` | n/a |
| `color.card` | #1E1F22 | `surfaceContainer` | n/a |
| `color.well` | #232427 | `surfaceContainerHigh`, inputs | n/a |
| `color.bar` | #161719 | top bar, rail | n/a |
| `color.line` | #3A3B3F | `outline` (decorative) | 1.57:1 |
| `color.ink` | #E9E6DE | `onSurface` | 14.1:1 on room |
| `color.dim` | #A9A7A1 | `onSurfaceVariant` | 7.3:1 |
| `color.faint` | #8C8A85 | tertiary text ≥ 14 sp | 5.1:1 |
| `color.amber` | #F2A33A | **`primary`**, focus, LEDs, warnings (with icon) | 8.4:1 on room |
| `color.onAmber` | #2A1A03 | `onPrimary` | 8.1:1 on amber |
| `color.amberHi` | #FFC56E | pressed primary | n/a |
| `color.green` | #7CC48A | success ("Saved"); panel LED green #62D36E | n/a |
| `color.error` | #FFB4AB | `error` | 10.4:1 |
| **FM-1 panel** | | | |
| `fm1.body.top/bot` | #5E6166 → #4A4D52 | body gradient | n/a |
| `fm1.tray` | #2B2C2F | LCD bezel, keybed tray | n/a |
| `fm1.silk` | **#E9E6DE** (web #C9C6BD) | silkscreen | 4.99:1 on #5E6166 (web value 3.64:1 fails) |
| `fm1.btn.top/bot` | #4B4E54 → #3B3D42 | button caps | n/a |
| `fm1.btn.ink` | **#E9E6DE** (web #9C9A94) | button legends | 6.7:1 (web value 2.97:1 fails) |
| `fm1.key.top/bot/line` | #6A5557 → #4F3F42, #8F7C7E | rose keys | n/a |
| **SMK-37 panel (v1.1)** | | | |
| `smk.body.top/bot/edge` | #222327 → #18191C, #3A3C41 | body | n/a |
| `smk.ink` / `smk.inkDim` | #B9BBBF / **#9A9DA2** (web #7D8085) | labels | 8.2:1 / ≥ 4.5:1 (web value 3.96:1 fails) |
| `smk.led` / `smk.ledBt` | #E9EEFC / #5B8CFF | LEDs | n/a |

"Flat high-contrast" look: body #2B2C2F, labels #FFFFFF, 2 dp key outlines, LED-on adds a ring.

### 9.3 Typography

| Role | Font | Spec |
|---|---|---|
| Chrome | Roboto Flex / system | M3 type scale |
| Eyebrows | same, 600 | 12 sp, +0.08 em, uppercase, `color.dim` (web `.eyebrow`) |
| Panel silkscreen | bundled condensed sans (e.g. Barlow Semi Condensed 600, OFL) | uppercase, 11–14 sp with panel scale, +0.04 em |
| Diagnostics, hashes | Roboto Mono | 12–13 sp |

### 9.4 Components and motion

- M3 for chrome: small top app bar, navigation rail, segmented buttons, filled/tonal buttons, assist chips, banners, bottom sheets, list-detail.
- Custom panel components: Encoder, Pot, PanelButton+LED, Keybed (single/two-tier/windowed), LcdView (SMK v1.1: Pad, Fader, Wheel).
- Motion: press feedback ≤ 1 frame; layout-mode changes crossfade 200 ms (no emulator interruption); nothing decorative on the panel.
- Icons: Material Symbols Rounded; MIDI = 5-pin DIN glyph.

---

## 10. Audio and latency UX

### 10.1 Scope

Output only. No input, so no feedback loop, input meter or mic permission. Latency here means **touch-to-sound** and **MIDI-to-sound**.

### 10.2 Output and routing

| Situation | UX |
|---|---|
| Route display | Audio screen: icon + name ("USB · Scarlett 2i2", "Wired headphones", "Phone speaker", "Bluetooth · Pixel Buds"), rate, buffer, estimated latency. |
| **Bluetooth output** | Once per BT device (can be turned off): "Bluetooth headphones add about 150–300 ms of delay to what you play on screen. For playing, use the speaker, wired or USB audio. Bluetooth is fine for listening." [OK] [Don't show again] |
| **Headphones unplugged / BT lost** | Output and emulation **pause** together (a running sequence resumes where it was). Paused scrim + [Resume]. |
| USB audio interface attached | Follows system routing; snackbar "Now playing through Scarlett 2i2". |
| **Audio focus** | Transient loss (call): pause, resume on gain. Permanent loss (other media): pause + scrim. Duck: −12 dB, no pause. |
| **Meter + clip** | L/R peak meter on the Audio screen; optional mini meter in the Load chip. **CLIP** when the firmware hits full scale, held 2 s; tip "This firmware is clipping. Turn MASTER down." Harbor adds **no gain or limiter** (fidelity). |
| **Loudness** | First boot of each firmware: MASTER at 50%. Android's headphone volume warning applies. |
| **Played over USB from a computer** | Audio is from the phone. The USB guide and the MIDI row both say so. |

### 10.3 Automatic clock and the performance banner

**Automatic clock (no user action).** Every second the engine measures load (emulation time ÷ audio budget). When a
firmware can't keep real time (load stays over ~87%, i.e. under 1.15× real time, with repeated underruns), the engine
snapshots flash, restarts the firmware one step lower (240 → 200 → 160 → 120 MHz) and keeps measuring. Firmware time
and tempo don't change; only the CPU headroom does. The chosen clock is stored per firmware hash per device, so the next
launch starts there. The user sees a single snackbar per firmware ("Running X0X at 160 MHz so it plays smoothly on this
phone." [Details]) and the clock in the Load chip, Library card and S5. No firmware is hidden or flagged before it runs.

**Banner, only for what the clock can't fix** (sustained ≥ 5 s):

| Condition | Banner | Primary action |
|---|---|---|
| load > 95% even at 120 MHz | "Even at 120 MHz this phone can't run X0X in real time. The desktop version of the emulator will play it." | [Copy a report] |
| dropouts, load ≤ 95%, Low latency buffer | "The sound drops out although the emulator has time to spare. A larger audio buffer adds a little delay to the keys." | **Use a larger buffer** |
| dropouts with Stable buffer | "The sound drops out on this device, even with the larger buffer." | [Copy a report] [Report on GitHub ↗] |
| user fixed the clock by hand and it can't keep up | "X0X can't keep up at 240 MHz on this phone." | **Use automatic clock** |

Secondary: [Copy a report], [Dismiss].

### 10.4 Latency targets (eng to confirm feasibility)

| Path | Target (reference 2022+ upper-mid phone, Low latency) |
|---|---|
| Touch-down to key visual | ≤ 16 ms (next frame) |
| Touch-down to sound | ≤ 30 ms |
| USB / peripheral-port MIDI to sound | ≤ 25 ms + DIN serialisation (≈ 1 ms per 3-byte message) |
| BLE MIDI to sound | ≤ 25 ms + BLE connection interval |

---

## 11. MIDI input and Harbor as a MIDI device

Harbor is **MIDI in only**. Nothing leaves Harbor as MIDI.

### 11.1 Routing model

```
 USB MIDI devices (host)        ─┐
 BLE MIDI devices               ─┤
 USB Peripheral Port (computer) ─┼─► [enable] ─► [channel remap] ─► (MIDI learn, 1.1) ─► merge ─► FM-1 MIDI IN jack
 Other apps' MIDI outputs       ─┤    per source   per source        intercepts here             (UART 31,250 baud,
 Harbor's own "MIDI IN" port    ─┘                                                                about 1 s queue)
```
- Merged sources stay well-formed (per-source running-status/SysEx parsing), so two players never corrupt each other's messages. SysEx passes through.
- Panic and automatic all-notes-off on a source disconnect.

### 11.2 Harbor as a MIDI device (MidiDeviceService)

| Property | Value |
|---|---|
| Device name | **"Harbor"** |
| Manufacturer / product | "Mark White" / "Harbor (FM-1 emulator)" (final strings: Q12) |
| Ports | **One input port, "MIDI IN"** (other apps send to Harbor). **No output port.** |
| Visibility | "Visible to other apps" (default on). Off disables the service component so Harbor leaves other apps' lists. |
| Connection feedback | Android doesn't name the client, so the UI shows "1 app connected" (open port count). |
| Background | A client connecting while Harbor is in the background or closed starts a foreground service (type `mediaPlayback`), boots the last firmware and posts a notification "Harbor is playing Felucca for another app" [Stop] [Open]. On the last disconnect: stop after 30 s unless "Keep playing in background" is on. Notification permission (Android 13+) is asked the first time this happens; if denied, the service still runs but the notification is silent. |

### 11.3 USB Peripheral Port (a computer plays Harbor)

- Android exposes the phone as a USB MIDI gadget when the user sets USB mode to **MIDI**. Harbor lists it as **"USB Peripheral Port"** (type: Computer), like any other source.
- Harbor **can't change the USB mode**, so it detects "connected to a computer, but no peripheral port" and shows the S4b hint with steps and [Open settings].
- Audio stays on the phone. A phone with one USB-C port can't host a USB keyboard at the same time.

### 11.4 MIDI learn (v1.1; UX reserved)

MIDI ▸ [MIDI learn…] ▸ learnable controls outlined on the panel ▸ tap one ▸ "Move a knob or press a key on your controller" ▸ binding chip.
CC (absolute) → MASTER; CC (relative: two's complement / binary offset, auto-detected, overridable) → encoder clicks; note or CC → button press/release.
Learned messages are **consumed** (not passed to the firmware) unless "Also send to firmware" is ticked. Bindings are stored **per firmware**.

---

## 12. Acceptance criteria (MVP)

**M1 Library**
1. Lists every FM-1 catalog entry (49 at time of writing) with name, version, author, licence, about and source link. SMK-37 entries aren't shown in v1.
2. Play on an uncached firmware downloads from its `package` URL with progress and Cancel; replaying needs no network.
3. Offline: cached firmwares play; others show "Needs internet" with Play disabled.
4. "Check for updates" offers a changed package as a **separate** version; the old save is untouched.
5. Search filters the full list within 100 ms per keystroke.
6. A firmware that Harbor has moved to a reduced clock on this device shows that clock on its card ("160 MHz here").

**M2 Local packages / BYO**
1. A `.fwsc` from Files, Downloads, Gmail or Drive (picker or "Open with") boots. An SMK-37 package is refused with the "coming later" message.
2. Stock flow: from the Library card to a running stock firmware in ≤ 4 taps once `FM-1.fwsc` is downloaded; the package is kept.
3. Invalid files give a named error; the running firmware continues.

**M3 Play**
1. All Appendix A.1 controls are present and operable in C-P, C-L, M and E on: 360×780 dp and 412×915 dp phones, 800×1280 dp tablet, 673×841 dp foldable.
2. No target below §5.3 (automated layout test per mode).
3. Rotation, fold, split-screen and theme change **don't reboot** the firmware; audio gap ≤ 50 ms; no stuck notes.
4. Ten simultaneous touches register (5-note chord + knob turn + button hold).
5. Glissando over all 27 keys (per tier in two-tier mode) presses each key exactly once per crossing.
6. At Normal sensitivity a 120 dp vertical drag = exactly 10 detents; a tap = exactly 1.
7. LCD pixel-exact at ≥ 30 fps whenever the firmware updates it; LEDs track `fm1_leds` at the same rate.

**M4 Audio / performance**
1. Sound starts automatically after boot.
2. Load and dropout figures update once per second (chip and Audio screen).
3. Induced overload (e.g. a CPU-heavy firmware, or a test hook that throttles the engine) steps the clock down within 10 s, with flash intact, no stuck notes and a single snackbar; the next launch starts at the stored clock with no dropouts.
4. A manual clock choice disables automatic for that firmware; "Reset to automatic" restores it.
5. Problems the clock can't fix give the right §10.3 banner within 10 s.
6. "Copy a report" puts a report with all §6.6 fields on the clipboard.
7. Touch-to-sound meets §10.4 on the reference device (eng loopback test).

**M5 Saves & backup**
1. Flash written by the firmware's own SAVE persists within 2 s and survives force-stop and device reboot.
2. A `.fm1save` exported from Harbor imports in the **web emulator** and the **plugin**, and vice versa (fixture `browser-v1.fm1save`).
3. Import shows a summary and replaces only on confirm; invalid files leave the setup intact.
4. Reset needs confirmation and restarts with pristine flash.
5. A save failure shows within 1 s with an "Export a copy" path.

**M6 MIDI input**
1. A class-compliant USB MIDI keyboard appears within 2 s of plug-in; after the system prompt, notes play.
2. A BLE MIDI keyboard can be scanned, connected and played, and reconnects automatically within 30 s of coming back in range.
3. Another app's MIDI output can be enabled as a source.
4. With the phone on a computer and USB mode set to MIDI, "USB Peripheral Port" appears and a desktop DAW (e.g. Ableton Live or Reaper) plays Harbor.
5. With the phone on a computer but USB not in MIDI mode, the S4b hint appears within 2 s, and [Open settings] lands in system settings.
6. Channel remap "2" moves note-ons from any channel to channel 2 (stock firmware responds).
7. The activity dot flashes for note/CC traffic and not for clock-only traffic.
8. Denying Nearby devices leaves USB, peripheral and app sources fully working, with a recoverable explanation.
9. Unplugging a source mid-chord leaves no stuck notes.

**M7 Harbor as a MIDI device**
1. "Harbor" with a single input port "MIDI IN" (and no output port) appears in at least two third-party MIDI apps on the reference device.
2. Notes from such an app play Harbor, including with Harbor in the background (notification shown).
3. "Visible to other apps" off removes Harbor from other apps' device lists (after their refresh).
4. When the last client disconnects and background play is off, the service stops within 30 s.

**M8 DX7 bank**: a 32-voice `.syx` loads into stock slots 1–32 and is persisted in the save; a wrong file gives an error.

**M9 Onboarding / help / legal**: first run reaches sound with no permission prompts; the disclaimer is in onboarding and About; each downloaded firmware's licence is listed; the USB guide is reachable from Help.

**M10 Accessibility**: TalkBack can press, hold and turn every panel control; chrome passes Accessibility Scanner; panel label contrast ≥ 4.5:1; chrome works at 200% font scale with no clipped text.

**M11 Routing**: the BT warning shows once per BT device; unplugging wired headphones pauses within 200 ms; a phone call pauses and the end of the call resumes.

**M12 No-sound notice**: CTL-1 shows the "makes no sound of its own" notice on boot.

---

## 13. Open questions for the eng lead

1. **Android host performance (GAP-7).** Can the AArch64 JIT (`jit_a64.rs`) run under Android's W^X/SELinux rules (e.g. memfd dual mapping)?
   Real-time factors at 240 MHz on reference devices (e.g. Pixel 7a/8, Galaxy S23, Tab S9 FE) per firmware? These numbers set the automatic clock's starting points and confirm the 240 MHz default.
2. **Panel rendering ownership.** The spec needs Compose-native controls (re-flow, semantics). Do we drop Rust `panel_draw`/`panel_hit` on Android and keep only the input API (`fm1_key/enc/pot`), mirroring the `panel_enc_id` mapping?
3. **LCD/LED delivery.** 60 Hz from the audio thread to UI without allocation (web caps at 30 Hz): affordable?
4. **Audio stack.** Oboe/AAudio MMAP exclusive; emulator renders 44.1 kHz. Resample to native 48 kHz? Frame counts for "Low latency" vs "Stable"? How do we measure output latency to display it?
5. **Background policy.** FGS `mediaPlayback` while a virtual-port client is connected or "Keep playing in background" is on: OK under Play policy and Android 14+ FGS rules? Battery cost of 240 MHz in the background?
6. **Persistence and `.fm1save`.** Reuse `fm1-plugin/src/savefile.rs` through the Rust core (preferred, guarantees interop) rather than porting `persistence.js`? Storage: per-SHA-256 sector files plus a Room index; atomic commit semantics?
7. **Config-change continuity.** Engine in a service or process singleton so rotation/fold never reboots; confirm the ≤ 50 ms gap target.
8. **Catalog and licensing.** Bundle a `firmwares.json` snapshot and refresh from the GitHub Pages URL? Does the Hub's permission to fetch packages from other pages extend to a native app? With no CORS barrier, may Harbor download M-VAVE's `FM-1.fwsc` for the user, or does it stay bring-your-own? April OS's package is `embedded` in its installer page: OK to extract in-app?
9. **Play Store policy** on downloading and running emulated firmware (not Android code), as ROM-based emulators do. Legal/policy review.
10. **MIDI timing.** Use `MidiReceiver.onSend` timestamps to place bytes at the right emulated frame (as the plugin does with host stamps)? Expected jitter, especially from the USB Peripheral Port?
11. **USB mode detection and the settings link.** Reliable signal for "connected to a computer but not in MIDI mode" (e.g. `BATTERY_PLUGGED_USB` and no peripheral-port `MidiDeviceInfo`)? How do we avoid false hints on USB wall chargers? Which settings screen can we open (no public intent for USB preferences)? Is the peripheral port's `MidiDeviceInfo` name stable across OEMs, so we can label it "USB Peripheral Port"?
12. **MidiDeviceService strings** (manufacturer/product) and whether disabling the component at runtime is reflected promptly in other apps' device lists.
13. **Expose through the bindings**: UART drop counter (GAP-1), LED buffer, halted flag, load metrics.
14. **BLE MIDI.** `MidiManager.openBluetoothDevice` reliability across OEMs; CompanionDeviceManager vs direct scan for pairing UX?
15. **Catalog metadata** (GAP-5/6): MIDI hints, "no sound of its own" flag, heaviness, tags. Do we upstream them to fm1-emu `firmwares-own.json` / the Hub, or keep an app-side overlay?
16. **Clock semantics.** Confirm firmware time stays correct at reduced clocks (`periph.rs` converts cycles to real time), so "Use 160 MHz" only changes headroom, never tempo.
17. **Palm rejection signals** on tablets (`MotionEvent` touch major / tool type): reliable enough?

**Answers from the implementation plan** (`implementation-plan.md`), so far:
- Q1: the JIT's Linux path (RWX `mmap` + cache maintenance) is what Android needs, behind a one-line `cfg` change offered upstream; memfd dual mapping is the fallback. M0 measures real-time factors on 2022+ devices.
- Q2: yes. Compose draws the panel; Rust exposes only the input/output API.
- Q4: AAudio via Rust's `ndk` crate, 44.1 kHz requested, resampled in the engine if the device insists on 48 kHz; the engine runs `lead` frames ahead of the callback (the plugin's model), and "Stable" is a larger lead.
- Q6: `.fm1save` is read and written by Rust code derived from `savefile.rs`, for interop.
- Q7: the engine lives in a process-wide singleton owned by a foreground `PlaybackService`; activities only attach to it.
- Q10: yes; MIDI bytes carry `System.nanoTime` stamps that the engine converts to frames.
- Q16: the clock changes CPU headroom only; timers are derived from cycles × Hz, so tempo is unchanged (to be verified in M0).

---

## Appendix A: control maps

### A.1 FM-1 (v1; ids from `panel.rs` / matrix)

| Group | Controls | Ids | Type |
|---|---|---|---|
| Encoders | SELECT, PRESETS, ALGORITHM, KNOB1, KNOB2, KNOB3, KNOB4 | matrix enc 0, 6, 1, 2, 3, 4, 5 | relative |
| Pot | MASTER | SARADC ch 4 (0–1023) | absolute |
| Buttons | OCT−, OCT+ | 0, 1 | momentary + LED |
| Buttons row 1 | FX, SCL, ENV, LFO, EDIT, GLO | 2–7 | momentary + LED |
| Buttons row 2 | HOME, SAVE, ARP, SEQ, PLAY, REC | 8–13 | momentary + LED |
| Keys | F3 … G5 (27) | 14–40 | momentary |
| LCD | 240×240 RGB565 | n/a | output |
| LEDs | 41 lines (buttons + keys) | 0–40 | output 0..1 |

Web keyboard map (for F4): A W S E D F T G Y H U J K O L P ; ' = C4–F5 · Z/X = OCT−/OCT+ · Space = PLAY · R = REC ·
1–0 = FX SCL ENV LFO EDIT GLO HOME SAVE ARP SEQ · ←/→ SELECT · ↓/↑ PRESETS · , / . ALGORITHM.

### A.2 SMK-37 (v1.1; ids from `smk.rs`)

| Group | Controls | Ids | Type |
|---|---|---|---|
| Keys | C3 … C6 (37), two contacts | 0–36 | momentary + velocity |
| Buttons left | KNOB BANK, FADER BANK, <, >, OCTAVE−, OCTAVE+ | 37–42 | momentary + LED |
| Buttons right top | ARP, NOTE REPEAT, SCALE, CHORD, GLOBE, BT | 43–48 | momentary + LED (BT blue) |
| Buttons right bottom | PATCH, PARA, FX, SEQ, SEQ PLAY, SEQ REC | 49–54 | momentary + LED |
| Encoders | K1–K8 | enc 0–7 | relative |
| Pads | 16 (top 0–7, bottom 8–15), RGB | analog 0–15 | pressure + RGB |
| Faders | F1–F4 | analog 16–19 | absolute |
| Wheels | Pitch (rest 535), Mod (rest 629) | analog 20, 21 | absolute |
| Pedal | jack | analog 22 | later (CC64) |

---

## Appendix B: key copy strings

| Key | String |
|---|---|
| disclaimer | "Harbor plays M-VAVE FM-1 firmwares. Not made by or affiliated with M-VAVE. Each firmware is its author's, under its own licence; Harbor hosts none of them." |
| onboarding.title | "Welcome to Harbor" |
| empty.play | "Nothing docked yet" |
| downloading | "Bringing %1$s into harbor…" |
| library.emptySearch | "No firmware by that name in the harbor." |
| byo.body | "Harbor can't include M-VAVE's firmware. Download FM-1.fwsc from M-VAVE, then open it here. Harbor keeps it for next time." |
| save.ramTip | "Patch edits live in the firmware's memory until you use its own SAVE. Everything it saves, Harbor keeps." |
| switch.tip | "Edits held only in the firmware's memory are lost when you switch. Use its own SAVE first." |
| noSound.banner | "%1$s is a MIDI controller: it makes no sound of its own in Harbor." |
| clock.badge | "%1$d MHz here" |
| clock.snackbar | "Running %1$s at %2$d MHz so it plays smoothly on this phone." |
| clock.tradeoff | "A lower clock gives the firmware less time to compute, so under heavy load it may drop voices or effects." |
| bt.warning | "Bluetooth headphones add about 150–300 ms of delay to what you play on screen. For playing, use the speaker, wired or USB audio." |
| usb.hint | "Connected to a computer by USB. To play Harbor from a DAW on the computer, set this phone's USB mode to MIDI." |
| usb.audioNote | "Sound still plays from the phone, not over the USB cable." |
| halted | "The emulated CPU halted. Restart the firmware, or copy a report for its author." |
| bgNotification | "Harbor is playing %1$s for another app." |
| smk.refused | "This is an SMK-37 firmware. SMK-37 support is coming in a later version of Harbor." |

---

## Appendix C: sources

- fm1-emu repository (studied locally, data only): `README.md`, `docs/recon.md`, `docs/stock-firmware.md`, `docs/wasm-persistence-plan.md`,
  `PLAN.md` (§5.13 Android speed, real-time factors), `crates/fm1-live/src/panel.rs`, `panel_smk.rs`, `crates/fm1-soc/src/periph.rs`, `smk.rs`, `lcd.rs`,
  `crates/fm1-web/src/lib.rs` (host API), `crates/fm1-plugin/README.md`, `web/index.html`, `web/main.js`, `web/style.css`,
  `web/firmwares.json`, `web/smk37/firmwares.json`, `web/smk37/index.html`.
- Live web emulator: https://joakimeriksson.github.io/fm1-emu/ (and /smk37/).
- Hardware specs and reviews: [Synth Anatomy review](https://synthanatomy.com/2026/07/m-vave-fm-1-review-low-budget-pocket-fm-ynthesizer-with-iconic-sounds.html),
  [Synthtopia announcement](https://www.synthtopia.com/content/2026/07/13/m-vave-introduces-fm-1-fm-pocket-synthesizer/),
  [Sonicstate roundup](https://sonicstate.com/news/2026/07/10/m-vave-fm-1-reviews-/), [Geeky Gadgets review](https://www.geeky-gadgets.com/m-vave-fm1-synthesizer-review/).
  Facts used: 6-op FM, 32 algorithms, 128 presets, 12 voices; 27-key silicone keybed; 7 encoders + volume; colour TFT; speaker muted by headphones;
  3.5 mm stereo out (mono engine/FX); USB-MIDI, BLE MIDI, TRS MIDI IN on hardware; 2000 mAh; 161.5 × 96.5 × 28.6 mm, 251 g.
