package com.markwhite.harbor.midi

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.markwhite.harbor.data.Settings
import com.markwhite.harbor.engine.NativeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

enum class SourceKind { Usb, Bluetooth, Computer, App }

/** A MIDI source Harbor can play from (ui-spec §6.5). */
data class MidiSource(
    val id: Int,
    val key: String,
    val name: String,
    val kind: SourceKind,
    val enabled: Boolean,
    /** -1: as sent */
    val remap: Int,
    val connected: Boolean,
    /** time of the last non-real-time message (System.nanoTime), for the activity dot */
    val lastActivity: Long = 0,
)

/**
 * Every MIDI source on the system, opened when enabled and fed into the emulated MIDI IN (each
 * through its own parser in the engine, so merged sources stay well-formed).
 */
class MidiHub(private val context: Context, private val settings: Settings) {
    private val manager: MidiManager? = context.getSystemService(MidiManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private val _sources = MutableStateFlow<List<MidiSource>>(emptyList())
    val sources: StateFlow<List<MidiSource>> = _sources
    /** System.nanoTime of the last message from any source (the MIDI chip's dot) */
    private val _activity = MutableStateFlow(0L)
    val activity: StateFlow<Long> = _activity
    /** USB connected to a computer but not in MIDI mode: show the "Set USB to MIDI" hint */
    private val _usbHint = MutableStateFlow(false)
    val usbHint: StateFlow<Boolean> = _usbHint

    private val infos = ConcurrentHashMap<Int, MidiDeviceInfo>()
    private val open = ConcurrentHashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
    private val activityAt = ConcurrentHashMap<Int, Long>()
    private var usbToComputer = false
    private var started = false

    fun start() {
        if (started || manager == null) return
        started = true
        manager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, executor, object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = added(device)
            override fun onDeviceRemoved(device: MidiDeviceInfo) = removed(device)
        })
        for (d in manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)) added(d)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(USB_STATE)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        refreshUsbHint()
    }

    private fun kindOf(d: MidiDeviceInfo): SourceKind = when (d.type) {
        MidiDeviceInfo.TYPE_BLUETOOTH -> SourceKind.Bluetooth
        MidiDeviceInfo.TYPE_VIRTUAL -> SourceKind.App
        // the phone as a USB gadget: its port carries what the computer sends
        else -> if (isPeripheralPort(d)) SourceKind.Computer else SourceKind.Usb
    }

    private fun isPeripheralPort(d: MidiDeviceInfo): Boolean {
        val p = d.properties
        val name = p.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()
        return d.type == MidiDeviceInfo.TYPE_USB && p.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, android.hardware.usb.UsbDevice::class.java) == null &&
            (name.contains("Peripheral", ignoreCase = true) || p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).isNullOrBlank())
    }

    private fun keyOf(d: MidiDeviceInfo): String {
        val p = d.properties
        return listOf(d.type, p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT),
            p.getString(MidiDeviceInfo.PROPERTY_NAME), p.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER)).joinToString("|")
    }

    private fun nameOf(d: MidiDeviceInfo): String {
        if (isPeripheralPort(d)) return "USB Peripheral Port"
        val p = d.properties
        return p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)).joinToString(" ").ifBlank { "MIDI device" }
    }

    private fun isOwn(d: MidiDeviceInfo): Boolean =
        d.type == MidiDeviceInfo.TYPE_VIRTUAL && d.properties.getParcelable("service_info", android.content.pm.ServiceInfo::class.java)?.packageName == context.packageName

    private fun added(d: MidiDeviceInfo) {
        // sources are devices with outputs (what they send, we receive); never our own port
        if (d.outputPortCount == 0 || isOwn(d)) return
        infos[d.id] = d
        val key = keyOf(d)
        val enabled = settings.midiEnabled(key) ?: settings.autoEnableMidi.value.also { settings.setMidiEnabled(key, it) }
        publish()
        if (enabled) openDevice(d)
        if (kindOf(d) == SourceKind.Computer) refreshUsbHint()
    }

    private fun removed(d: MidiDeviceInfo) {
        infos.remove(d.id)
        closeDevice(d.id)
        publish()
        refreshUsbHint()
    }

    private fun openDevice(d: MidiDeviceInfo) {
        if (open.containsKey(d.id) || manager == null) return
        manager.openDevice(d, { device ->
            if (device == null) {
                Log.w(TAG, "could not open ${nameOf(d)}")
                return@openDevice
            }
            connect(d, device)
        }, handler)
    }

    private fun connect(d: MidiDeviceInfo, device: MidiDevice) {
        val source = d.id
        NativeEngine.midiOpen(source, settings.midiRemap(keyOf(d)))
        val ports = (0 until d.outputPortCount).mapNotNull { device.openOutputPort(it) }
        val receiver = object : MidiReceiver() {
            override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                if (NativeEngine.midiData(source, msg, offset, count, timestamp)) {
                    val now = System.nanoTime()
                    activityAt[source] = now
                    _activity.value = now
                }
            }
        }
        ports.forEach { it.connect(receiver) }
        open[d.id] = device to ports
        publish()
    }

    private fun closeDevice(id: Int) {
        val (device, ports) = open.remove(id) ?: return
        NativeEngine.midiClose(id) // release what it held: no stuck notes
        ports.forEach { runCatching { it.close() } }
        runCatching { device.close() }
        publish()
    }

    fun setEnabled(source: MidiSource, on: Boolean) {
        settings.setMidiEnabled(source.key, on)
        val d = infos[source.id] ?: return
        if (on) openDevice(d) else closeDevice(d.id)
        publish()
    }

    fun setRemap(source: MidiSource, ch: Int) {
        settings.setMidiRemap(source.key, ch)
        NativeEngine.midiRemap(source.id, ch)
        publish()
    }

    /** Connect a Bluetooth LE MIDI device found by a scan. */
    fun connectBluetooth(device: BluetoothDevice, done: (Boolean) -> Unit) {
        val m = manager ?: return done(false)
        m.openBluetoothDevice(device, { dev ->
            if (dev == null) return@openBluetoothDevice done(false)
            val info = dev.info
            infos[info.id] = info
            settings.setMidiEnabled(keyOf(info), true)
            connect(info, dev)
            done(true)
        }, handler)
    }

    fun panic() = NativeEngine.panic()

    /** Re-read activity times into the list (the screen calls this a few times a second). */
    fun publish() {
        _sources.value = infos.values.sortedBy { it.id }.map { d ->
            val key = keyOf(d)
            MidiSource(
                id = d.id, key = key, name = nameOf(d), kind = kindOf(d),
                enabled = settings.midiEnabled(key) ?: true, remap = settings.midiRemap(key),
                connected = open.containsKey(d.id), lastActivity = activityAt[d.id] ?: 0,
            )
        }
    }

    // ---------------------------------------------------------- USB connected to a computer

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == USB_STATE) {
                usbToComputer = i.getBooleanExtra("connected", false) && i.getBooleanExtra("configured", true)
            }
            refreshUsbHint()
        }
    }

    private fun refreshUsbHint() {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val onUsb = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_USB
        val peripheral = infos.values.any { kindOf(it) == SourceKind.Computer }
        // a USB charger shows "plugged USB" too, but only a computer configures the USB link
        _usbHint.value = onUsb && usbToComputer && !peripheral
    }

    companion object {
        private const val TAG = "MidiHub"
        /** UsbManager's sticky broadcast (hidden constant): extras "connected", "configured", "midi". */
        private const val USB_STATE = "android.hardware.usb.action.USB_STATE"
        val BLE_MIDI_SERVICE: java.util.UUID = java.util.UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }
}
