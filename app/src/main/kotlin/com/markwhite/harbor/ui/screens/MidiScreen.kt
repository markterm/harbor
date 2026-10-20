package com.markwhite.harbor.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.data.FirmwareNotes
import com.markwhite.harbor.midi.HarborMidiService
import com.markwhite.harbor.midi.MidiHub
import com.markwhite.harbor.midi.MidiSource
import com.markwhite.harbor.midi.SourceKind
import com.markwhite.harbor.ui.UiState
import com.markwhite.harbor.ui.theme.Tokens
import kotlinx.coroutines.delay

/** MIDI (ui-spec §6.5): what plays the FM-1's MIDI IN, and Harbor as a MIDI device. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MidiScreen(app: HarborApp, ui: UiState, onBack: () -> Unit) {
    val context = LocalContext.current
    val midi = app.midi
    val sources by midi.sources.collectAsState()
    val usbHint by midi.usbHint.collectAsState()
    val clients by HarborMidiService.clients.collectAsState()
    val visible by app.settings.visibleToApps.collectAsState()
    val background by app.settings.keepPlayingInBackground.collectAsState()
    val autoEnable by app.settings.autoEnableMidi.collectAsState()
    var hintDismissed by remember { mutableStateOf(false) }
    var usbGuide by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.nanoTime()) }
    // the activity dots: refresh the sources' last-message times a few times a second
    LaunchedEffect(Unit) { while (true) { midi.publish(); now = System.nanoTime(); delay(120) } }

    SubScreen("MIDI", onBack) {
        if (usbHint && !hintDismissed) {
            InfoCard {
                Text("ⓘ Connected to a computer by USB", style = MaterialTheme.typography.titleSmall)
                Text("To play Harbor from a DAW on the computer, set this phone's USB mode to MIDI.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalButton(onClick = { usbGuide = true }) { Text("Show me how") }
                    OutlinedButton(onClick = { openUsbSettings(context) }) { Text("Open settings") }
                    TextButton(onClick = { hintDismissed = true }) { Text("Dismiss", maxLines = 1, softWrap = false) }
                }
            }
        }
        Section("Play from: the FM-1's MIDI IN") {
            sources.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
                SourceRow(midi, s, now)
            }
            if (sources.isEmpty()) {
                Text("Nothing connected", style = MaterialTheme.typography.titleSmall)
                Text("Plug in a USB keyboard, pair a Bluetooth one, or connect a computer.", style = MaterialTheme.typography.bodySmall, color = Tokens.dim)
            }
            HorizontalDivider(color = Tokens.line.copy(alpha = 0.6f))
            SwitchRow("Enable new devices automatically", autoEnable) { app.settings.setAutoEnableMidi(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { scan = true }) { Text("Pair Bluetooth…") }
            OutlinedButton(onClick = { midi.panic() }) { Text("All notes off") }
        }

        Section("Harbor as a MIDI device") {
            SwitchRow("Visible to other apps", visible, "\"Harbor\" · input \"MIDI IN\"") { on ->
                app.settings.setVisibleToApps(on)
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, HarborMidiService::class.java),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
            Text(if (clients == 0) "No app connected" else "$clients app${if (clients > 1) "s" else ""} connected ⦿")
            SwitchRow("Keep playing in background", background, onChange = rememberBackgroundToggle(app.settings))
        }

        val id = ui.docked?.entry?.id
        if (id != null) {
            Section("For ${ui.shortName}") {
                Text(FirmwareNotes.midiHint(id) ?: "No special MIDI needs known.")
                FirmwareNotes.preferredChannel(id)?.let { ch ->
                    OutlinedButton(onClick = { sources.forEach { midi.setRemap(it, ch) } }) { Text("Use channel ${ch + 1} for every source") }
                }
            }
        }
        TextButton(onClick = { usbGuide = true }) { Text("Play Harbor from a computer (USB)…") }
    }

    if (usbGuide) UsbGuide { usbGuide = false }
    if (scan) BluetoothScanSheet(midi) { scan = false }
}

@Composable
private fun SourceRow(midi: MidiHub, s: MidiSource, now: Long) {
    var menu by remember { mutableStateOf(false) }
    val lit = s.lastActivity != 0L && now - s.lastActivity < 150_000_000
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(if (lit) Tokens.amber else Tokens.line) }
            Column(Modifier.weight(1f)) {
                Text(s.name)
                Text(
                    when (s.kind) {
                        SourceKind.Usb -> "USB"
                        SourceKind.Bluetooth -> "Bluetooth"
                        SourceKind.Computer -> "Computer (USB)"
                        SourceKind.App -> "App"
                    } + if (s.enabled && !s.connected) " · connecting…" else "",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.dim,
                )
            }
            HarborSwitch(s.enabled, onChange = { midi.setEnabled(s, it) })
        }
        Box {
            TextButton(onClick = { menu = true }) { Text("Channel: " + if (s.remap < 0) "as sent" else "${s.remap + 1}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("As sent") }, onClick = { midi.setRemap(s, -1); menu = false })
                for (ch in 0 until 16) DropdownMenuItem(text = { Text("Channel ${ch + 1}") }, onClick = { midi.setRemap(s, ch); menu = false })
            }
        }
    }
}

private fun openUsbSettings(context: Context) {
    // (there's no public intent for the USB preferences screen; the steps cover the rest)
    val intents = listOf(Intent("android.settings.USB_SETTINGS"), Intent(Settings.ACTION_SETTINGS))
    for (i in intents) {
        if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

@Composable
private fun UsbGuide(onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Play Harbor from a computer") },
        text = {
            Text(
                "1. Keep the phone plugged into the computer.\n" +
                    "2. Open the \"Charging this device via USB\" notification, or Settings ▸ Connected devices ▸ USB.\n" +
                    "3. Choose \"MIDI\".\n" +
                    "4. Back in Harbor, \"USB Peripheral Port\" appears under Play from.\n" +
                    "5. In your DAW, pick this phone as the MIDI output for a track.\n\n" +
                    "ⓘ Sound still plays from the phone: its speaker, headphones or audio interface, not over the USB cable.\n" +
                    "ⓘ While USB is in MIDI mode the phone can't use a USB MIDI keyboard on the same port.",
            )
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = { openUsbSettings(context) }) { Text("Open settings") } },
    )
}

/** Scan for BLE-MIDI devices and connect one (ui-spec S4a). Asks for Nearby devices first. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
private fun BluetoothScanSheet(midi: MidiHub, onDone: () -> Unit) {
    val context = LocalContext.current
    val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    fun granted() = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    val found = remember { mutableStateListOf<BluetoothDevice>() }
    var scanning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }

    if (allowed && adapter?.isEnabled == true) {
        DisposableEffect(Unit) {
            val scanner = adapter.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(type: Int, r: ScanResult) {
                    if (found.none { it.address == r.device.address }) found += r.device
                }
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MidiHub.BLE_MIDI_SERVICE)).build()
            runCatching { scanner?.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb) }
            scanning = true
            onDispose { runCatching { scanner?.stopScan(cb) }; scanning = false }
        }
        LaunchedEffect(Unit) { delay(20_000); scanning = false }
    }

    ModalBottomSheet(onDismissRequest = onDone) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pair a Bluetooth MIDI device", style = MaterialTheme.typography.titleMedium)
            when {
                !allowed -> {
                    Text("Harbor needs \"Nearby devices\" to find Bluetooth MIDI devices. USB, app and computer sources work without it.")
                    FilledTonalButton(onClick = { ask.launch(perms) }) { Text("Allow") }
                }
                adapter == null -> Text("This device has no Bluetooth.")
                !adapter.isEnabled -> {
                    Text("Bluetooth is off.")
                    FilledTonalButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) { Text("Turn on Bluetooth") }
                }
                else -> {
                    if (scanning) {
                        Text("Scanning… (stops after 20 s)")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    for (d in found) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(runCatching { d.name }.getOrNull() ?: d.address, Modifier.weight(1f))
                            TextButton(onClick = {
                                status = "Connecting…"
                                midi.connectBluetooth(d) { ok -> status = if (ok) "Connected." else "Couldn't connect. Put it in pairing mode and try again." }
                            }) { Text("Connect") }
                        }
                    }
                    if (found.isEmpty() && !scanning) Text("Not listed? Put it in pairing mode, and disconnect it from other apps.", color = Tokens.dim)
                    status?.let { Text(it) }
                }
            }
            Row { Box(Modifier.weight(1f)); TextButton(onClick = onDone) { Text("Done") } }
        }
    }
}
