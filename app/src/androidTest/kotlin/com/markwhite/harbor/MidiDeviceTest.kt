package com.markwhite.harbor

import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.markwhite.harbor.engine.Phase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Harbor as a MIDI device (ui-spec M7): another app sees "Harbor" with one input port, and what
 * it sends reaches the emulated MIDI IN. Needs network the first time (it downloads Felucca).
 */
@RunWith(AndroidJUnit4::class)
class MidiDeviceTest {
    private val app: HarborApp = ApplicationProvider.getApplicationContext()

    private fun waitFor(timeoutMs: Long, f: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (f()) return true
            Thread.sleep(100)
        }
        return false
    }

    @Test
    fun anotherAppCanPlayHarbor() {
        val felucca = app.catalog.byId("felucca")!!
        app.harbor.play(felucca)
        assertTrue("Felucca boots", waitFor(120_000) { app.harbor.phase.value == Phase.Running || app.harbor.phase.value is Phase.Paused })
        app.harbor.resume()

        val mm = app.getSystemService(MidiManager::class.java)
        val info = mm.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).firstOrNull {
            it.type == MidiDeviceInfo.TYPE_VIRTUAL && it.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) == "Harbor (FM-1 emulator)"
        }
        assertNotNull("Harbor is listed as a MIDI device", info)
        info!!
        assertTrue("one input port, no output port", info.inputPortCount == 1 && info.outputPortCount == 0)

        val opened = CompletableFuture<android.media.midi.MidiDevice>()
        mm.openDevice(info, { opened.complete(it) }, Handler(Looper.getMainLooper()))
        val device = opened.get(10, TimeUnit.SECONDS)
        val port = device.openInputPort(0)!!
        val before = app.harbor.status.value.midiIn
        port.send(byteArrayOf(0x90.toByte(), 60, 100), 0, 3)
        Thread.sleep(300)
        port.send(byteArrayOf(0x80.toByte(), 60, 0), 0, 3)
        assertTrue("the note reached the MIDI IN jack", waitFor(5_000) { app.harbor.status.value.midiIn >= before + 6 })
        port.close()
        device.close()
        runBlocking { app.harbor.flushSave() }
    }
}
