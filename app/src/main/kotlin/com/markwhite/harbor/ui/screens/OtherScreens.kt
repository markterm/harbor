package com.markwhite.harbor.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.R
import com.markwhite.harbor.data.FirmwareEntry
import com.markwhite.harbor.data.KeybedMode
import com.markwhite.harbor.data.KnobGesture
import com.markwhite.harbor.ui.Screen
import com.markwhite.harbor.ui.theme.Tokens

const val DISCLAIMER = "Harbor plays M-VAVE FM-1 firmwares. Not made by or affiliated with M-VAVE. " +
    "Each firmware is its author's, under its own licence; Harbor hosts none of them."

/** Controls & display (ui-spec §6.7). */
@Composable
fun ControlsScreen(app: HarborApp, onBack: () -> Unit) {
    val s = app.settings
    SubScreen("Controls & display", onBack) {
        Section("Knobs") {
            Choice("Gesture", listOf(KnobGesture.Vertical to "Drag up/down", KnobGesture.Circular to "Circular", KnobGesture.Both to "Both"),
                s.knobGesture.collectAsState().value, s::setKnobGesture)
            Choice("Sensitivity", listOf(18f to "Fine", 12f to "Normal", 8f to "Fast"), s.knobDpPerDetent.collectAsState().value, s::setKnobDpPerDetent)
            SwitchRow("Tap a knob to step", s.tapToStep.collectAsState().value, "Top or right half +1, bottom or left −1", s::setTapToStep)
        }
        Section("Keys") {
            Choice("Keybed in phone portrait", listOf(KeybedMode.TwoTiers to "Two tiers", KeybedMode.SingleRow to "Single row"),
                s.keybedMode.collectAsState().value, s::setKeybedMode)
            Choice("Note names on keys", listOf(0 to "Off", 1 to "C only", 2 to "All"), s.noteNames.collectAsState().value, s::setNoteNames)
        }
        Section("Haptics") {
            SwitchRow("Knob detents", s.hapticKnobs.collectAsState().value, onChange = s::setHapticKnobs)
            SwitchRow("Buttons", s.hapticButtons.collectAsState().value, onChange = s::setHapticButtons)
            SwitchRow("Keys", s.hapticKeys.collectAsState().value, onChange = s::setHapticKeys)
        }
        Section("Display") {
            Choice("Panel finish", com.markwhite.harbor.ui.panel.Finishes.map { it.id to it.label }, s.finish.collectAsState().value, s::setFinish)
            SwitchRow("Smooth LCD scaling", s.smoothLcd.collectAsState().value, "Off: pixel-exact", s::setSmoothLcd)
            SwitchRow("Immersive in landscape", s.immersive.collectAsState().value, "Hide the status bar while playing", s::setImmersive)
        }
        Section("Accessibility") {
            SwitchRow("Hold assist", s.holdAssist.collectAsState().value, "A tap latches a button down; the next tap releases it", s::setHoldAssist)
            SwitchRow("Flat high-contrast panel", s.flatPanel.collectAsState().value, "Plain keys with strong outlines", s::setFlatPanel)
        }
    }
}

@Composable
fun SettingsHub(go: (Screen) -> Unit, onBack: () -> Unit) {
    SubScreen("Settings", onBack) {
        InfoCard {
            val items = listOf(
                Triple("MIDI", "Devices, channels, Harbor as a MIDI device", Screen.Midi),
                Triple("Audio & performance", "Output, buffer, clock, load", Screen.Audio),
                Triple("Controls & display", "Knobs, keys, haptics, panel finish", Screen.Controls),
                Triple("Help", "Playing, saving, known limits", Screen.Help),
                Triple("About & licences", "fm1-emu, firmware authors, open source", Screen.About),
            )
            items.forEachIndexed { i, (label, sub, screen) ->
                if (i > 0) HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
                Row(Modifier.fillMaxWidth().clickable { go(screen) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Tokens.dim)
                }
            }
        }
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    SubScreen("Help", onBack) {
        Section("Controls") {
            Text("The firmware decides what each button does: Harbor passes every press, release and turn through, exactly as the hardware would.")
            Bullets(
                "Knobs are endless: drag up or down (or around, in settings). Their value is on the firmware's screen.",
                "MASTER is the volume in most firmwares.",
                "Use several fingers: chords, or hold a key and turn a knob.",
                "Slide across the keys for a glissando.",
                "Swipe up from the bottom edge for Android's navigation while playing.",
            )
        }
        Section("Saving") {
            Text("Patches live in the firmware. Use its own SAVE and Harbor keeps everything it writes to flash, per firmware version, automatically.")
        }
        Section("Recording") {
            Text("The red dot at the top records what the firmware plays, MASTER included, as a WAV file (44.1 kHz, 16-bit, as the FM-1 makes it). " +
                "Tap the timer to stop. Recordings go to Music/Harbor, where music and file apps find them, and a take carries on if you switch apps.")
            Text("The panel's own REC button belongs to the firmware: it records into the firmware's sequencer, not audio.", color = Tokens.dim)
        }
        Section("Play from a computer (USB)") {
            Text("Plug the phone into the computer, set its USB mode to MIDI (the \"Charging this device via USB\" notification, or Settings ▸ Connected devices ▸ USB), " +
                "then pick the phone as a MIDI output in your DAW. Sound plays from the phone, not over the cable.")
        }
        Section("Known limits") {
            Bullets(
                "No MIDI out: nothing leaves Harbor as MIDI.",
                "USB and Bluetooth features inside firmwares don't work.",
                "No microphone input (sampler firmwares can't record).",
                "M-VAVE's factory patch bank isn't included; load a DX7 bank instead.",
                "Some firmwares ignore plain note-ons.",
                "TalkBack can't read the firmware's screen.",
                "SMK-37 firmwares arrive in a later version.",
            )
        }
    }
}

@Composable
private fun Bullets(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (l in lines) Row {
            Text("•", color = Tokens.amber, modifier = Modifier.width(16.dp))
            Text(l)
        }
    }
}

@Composable
fun AboutScreen(app: HarborApp, onBack: () -> Unit) {
    val context = LocalContext.current
    val installed by app.store.installed.collectAsState()
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    SubScreen("About & licences", onBack) {
        InfoCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(64.dp))
                Column {
                    Text("Harbor", style = MaterialTheme.typography.headlineSmall)
                    Text("Version $version", color = Tokens.dim)
                }
            }
            Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        }
        Section("Emulator") {
            Text("Harbor runs fm1-emu by Joakim Eriksson (MIT): the FM-1's CPU, chip, screen, keys, knobs and audio.")
            LinkButton("github.com/joakimeriksson/fm1-emu", "https://github.com/joakimeriksson/fm1-emu")
            Text(FM1_EMU_LICENSE, style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        }
        Section("Firmware on this device") {
            if (installed.isEmpty()) Text("None yet. Harbor hosts no firmware; each one downloads from its author.", color = Tokens.dim)
            for (p in installed) {
                val e = app.catalog.byId(p.catalogId)
                Column {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text("${e?.author ?: "opened from a file"} · ${e?.license ?: "licence unknown"}", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                }
            }
        }
        Section("Privacy") {
            Text(PRIVACY_SUMMARY, style = MaterialTheme.typography.bodySmall)
        }
        Section("Thanks") {
            Text("To the FM-1 reverse-engineering and firmware community: FM-1-RE, Felucca and its many forks, the FM-1 Firmware Hub, and every firmware's author.",
                style = MaterialTheme.typography.bodySmall)
            Text("Open-source components: AndroidX and Jetpack Compose (Apache-2.0), Kotlin and kotlinx (Apache-2.0), Barlow Condensed (OFL-1.1), " +
                "Rust crates jni, ndk, rtrb, serde, base64, sha2 (MIT/Apache-2.0).",
                style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        }
    }
}

/** In-app summary of docs/privacy-policy.md: keep the two in step. */
private const val PRIVACY_SUMMARY = "Harbor has no account, ads, analytics or tracking, and sends nothing about you anywhere. " +
    "It fetches the firmware lists from fm1-emu's site and the FM-1 Firmware Hub, the firmwares' screenshots from the Hub, " +
    "and a firmware from where its author publishes it when you choose one; " +
    "those servers see an ordinary web request. Firmwares, their saves and your settings stay on this device unless you export a backup. " +
    "Recordings are saved to the Music/Harbor folder on this device and go nowhere unless you share them. " +
    "Bluetooth is used only to find and play Bluetooth MIDI instruments, when you pair one, never for location. " +
    "\"Copy a report\" puts this device's model and Harbor's diagnostics on your clipboard, and only when you tap it."

private const val FM1_EMU_LICENSE = """MIT License

Copyright (c) 2026 Joakim Eriksson

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE."""

/** First run (ui-spec §6.1): three steps, no permission prompts, Felucca preselected. */
@Composable
fun Onboarding(app: HarborApp, onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val catalog by app.catalog.entries.collectAsState()
    val suggestions = listOf("felucca", "x0x", "obervave", "amb").mapNotNull { id -> catalog.firmwares.firstOrNull { it.id == id } }
    var pick by remember { mutableStateOf<FirmwareEntry?>(suggestions.firstOrNull()) }
    fun finish(play: Boolean) {
        onDone()
        if (play) (pick ?: catalog.firmwares.firstOrNull { it.id == catalog.default })?.let { app.harbor.play(it) }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${step + 1} / 3", color = Tokens.dim)
            when (step) {
                0 -> {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
                    Text("Welcome to Harbor", style = MaterialTheme.typography.headlineMedium)
                    Text("Plays M-VAVE FM-1 firmwares on your phone or tablet, unchanged: Felucca, SLOOP, Skiff and ${catalog.firmwares.size - 3} more.")
                    Text("Not made by or affiliated with M-VAVE.", color = Tokens.dim)
                }
                1 -> {
                    Text("Pick a firmware to start", style = MaterialTheme.typography.headlineSmall)
                    for (e in suggestions) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = if (pick == e) Tokens.well else Tokens.card),
                            modifier = Modifier.fillMaxWidth().clickable { pick = e },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text((if (pick == e) "● " else "○ ") + e.title, style = MaterialTheme.typography.titleMedium)
                                Text(e.about, style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                            }
                        }
                    }
                    Text("Browse all ${catalog.firmwares.size} later from the firmware title on the Play screen.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
                }
                else -> {
                    Text("How to play", style = MaterialTheme.typography.headlineSmall)
                    Text("• Drag a knob up or down.\n• Use several fingers: chords, or hold a key and turn a knob.\n" +
                        "• Patches live in the firmware. Use its SAVE button and Harbor keeps what it saves.")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { finish(true) }) { Text("Skip") }
                Spacer(Modifier.weight(1f))
                if (step < 2) Button(onClick = { step++ }) { Text("Next") }
                else Button(onClick = { finish(true) }) { Text("Start playing") }
            }
        }
    }
}
