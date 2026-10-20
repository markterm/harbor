package com.markwhite.harbor.ui.screens

import androidx.compose.foundation.layout.padding
import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.BufferMode
import com.markwhite.harbor.engine.EngineStatus
import com.markwhite.harbor.engine.NativeEngine
import com.markwhite.harbor.ui.UiState
import com.markwhite.harbor.ui.theme.Mono
import com.markwhite.harbor.ui.theme.Tokens
import kotlin.math.log10

/** Audio & performance (ui-spec §6.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioScreen(app: HarborApp, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val s = ui.status
    val buffer by app.settings.bufferMode.collectAsState()
    val background by app.settings.keepPlayingInBackground.collectAsState()
    val screenOn by app.settings.keepScreenOn.collectAsState()
    val btWarning by app.settings.btWarning.collectAsState()
    var clipHeld by remember { mutableStateOf(0L) }
    LaunchedEffect(s.clip) { if (s.clip) clipHeld = System.currentTimeMillis() }
    val history = remember { mutableStateListOf<String>() }
    LaunchedEffect(s.speed) {
        if (s.speed > 0) {
            history += "%.2f".format(s.speed)
            if (history.size > 30) history.removeAt(0)
        }
    }

    SubScreen("Audio & performance", onBack) {
        Section("Output") {
            Text(routeName(context, s.deviceId), style = MaterialTheme.typography.titleMedium)
            Text("${s.sampleRate} Hz · ${s.bufferFrames} frames" + (if (s.exclusive) " · exclusive" else "") + " · about ${s.latencyMs.toInt()} ms to the speaker",
                style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            Meter("L", s.peakL)
            Meter("R", s.peakR)
            if (System.currentTimeMillis() - clipHeld < 2000) {
                Text("CLIP · This firmware is clipping. Turn MASTER down.", color = Tokens.error)
            }
            Choice("Audio buffer", listOf(BufferMode.LowLatency to "Low latency", BufferMode.Stable to "Stable"), buffer) { app.harbor.setBufferMode(it) }
            Text("Stable adds a little delay to the keys but rides out a busy device.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
        }

        Section("Emulation") {
            ClockPicker(app, s)
            Text("A lower clock gives the firmware less time to compute, so under heavy load it may drop voices or effects.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            val load = s.load.coerceIn(0.0, 1.5)
            Text("Load ${(load * 100).toInt()}% of the audio time" + if (s.speed > 0) " (${"%.2f".format(s.speed)}× real time)" else "")
            LinearProgressIndicator(progress = { load.toFloat().coerceAtMost(1f) }, Modifier.fillMaxWidth(),
                color = if (load > 0.8) Tokens.amber else Tokens.green)
            Text("Frames dropped: ${s.starved}" + if (s.late > 0) " (${s.late} in the last second)" else "")
            Text(if (s.jit) "Compiling hot code (JIT)" else if (NativeEngine.jitAvailable()) "Interpreting (JIT off)" else "Interpreting (this device has no JIT: x86, or executable memory refused)",
                style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            OutlinedButton(onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Harbor report", report(context, ui, history)))
            }) { Text("Copy a report") }
        }

        Section("Background") {
            SwitchRow("Keep playing in background", background, "Shows a notification while it plays", rememberBackgroundToggle(app.settings))
            SwitchRow("Keep screen on while playing", screenOn) { app.settings.setKeepScreenOn(it) }
            SwitchRow("Bluetooth latency warning", btWarning, "Bluetooth adds a delay too long for playing live") { app.settings.setBtWarning(it) }
        }
        Text("No app volume: MASTER is the volume, and the phone's volume keys set media volume.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.dim, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun Meter(label: String, peak: Float) {
    val db = if (peak > 1e-5f) 20 * log10(peak) else -100f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Mono)
        LinearProgressIndicator(progress = { ((db + 60f) / 60f).coerceIn(0f, 1f) }, Modifier.weight(1f), color = if (peak >= 0.999f) Tokens.error else Tokens.green)
        Text(if (db <= -99f) "−∞ dB" else "${db.toInt()} dB", style = Mono, modifier = Modifier.width(56.dp))
    }
}

@Composable
private fun ClockPicker(app: HarborApp, s: EngineStatus) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = app.harbor.docked.value != null) {
            Text("Clock: " + if (s.autoClock) "Automatic (${s.mhz} MHz)" else "${s.mhz} MHz")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Automatic (starts at 240, steps down if needed)") }, onClick = { open = false; app.harbor.setClock(240, true) })
            for (c in listOf(240, 200, 160, 120)) {
                DropdownMenuItem(text = { Text("$c MHz" + if (c < 240) " (reduced, for heavy firmwares)" else "") }, onClick = { open = false; app.harbor.setClock(c, false) })
            }
        }
    }
}

fun routeName(context: android.content.Context, id: Int): String {
    val am = context.getSystemService(AudioManager::class.java)
    val d = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        ?: return "Output"
    return when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth · ${d.productName}"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB · ${d.productName}"
        else -> d.productName.toString()
    }
}

/** Is the current output Bluetooth (for the latency warning)? Its name, or null. */
fun bluetoothRoute(context: android.content.Context, id: Int): String? {
    val d = context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id } ?: return null
    return if (d.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)) d.productName.toString() else null
}

private fun report(context: android.content.Context, ui: UiState, history: List<String>): String {
    val s = ui.status
    return buildString {
        appendLine("Harbor report")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Firmware: ${ui.title} (${ui.docked?.pkg?.sha ?: "-"})")
        appendLine("Clock: ${s.mhz} MHz, automatic ${s.autoClock}, steps ${s.clockSteps}; JIT ${s.jit}")
        appendLine("Output: ${routeName(context, s.deviceId)}, ${s.sampleRate} Hz, burst ${s.framesPerBurst}, buffer ${s.bufferFrames}, exclusive ${s.exclusive}, lead ${s.leadFrames}")
        appendLine("Speed (x real time, last 30 s): ${history.joinToString(" ")}")
        appendLine("Dropped frames: ${s.starved}; AAudio xruns ${s.xruns}; MIDI bytes dropped ${s.midiDropped}")
        appendLine()
        appendLine("Recent events:")
        for (line in com.markwhite.harbor.engine.EventLog.recent()) appendLine(line)
    }
}
