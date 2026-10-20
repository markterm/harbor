package com.markwhite.harbor.midi

import android.media.midi.MidiDeviceService
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiReceiver
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.engine.NativeEngine
import com.markwhite.harbor.service.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Harbor as a MIDI device other apps can play: one input port, "MIDI IN" (res/xml/
 * midi_device_info.xml), straight into the emulated MIDI IN. A client connecting starts the
 * playback service, which boots the last firmware if none is docked (ui-spec §11.2).
 */
class HarborMidiService : MidiDeviceService() {
    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            NativeEngine.midiData(SOURCE_ID, msg, offset, count, timestamp)
        }
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = arrayOf(receiver)

    override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
        // (Android doesn't say which app; an open input port is a connected client)
        val n = (0 until status.deviceInfo.inputPortCount).count { status.isInputPortOpen(it) }
        val was = _clients.value
        _clients.value = n
        val app = application as HarborApp
        if (n > 0 && was == 0) {
            app.harbor.ensureStarted()
            NativeEngine.midiOpen(SOURCE_ID, -1)
            PlaybackService.startForClient(this)
        } else if (n == 0 && was > 0) {
            NativeEngine.midiClose(SOURCE_ID)
            PlaybackService.clientsGone(this)
        }
    }

    companion object {
        /** The engine's MIDI source id for this port (device ids from MidiManager are small). */
        const val SOURCE_ID = 1_000_000

        private val _clients = MutableStateFlow(0)
        /** Apps connected to Harbor's MIDI IN. */
        val clients: StateFlow<Int> = _clients
    }
}
