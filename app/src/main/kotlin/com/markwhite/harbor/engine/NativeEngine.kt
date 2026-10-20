package com.markwhite.harbor.engine

import java.nio.ByteBuffer

/**
 * libharbor.so (native/harbor-jni): the emulated FM-1 on its own thread, the AAudio output and the
 * `.fm1save` codec. One engine per process; [Harbor] is the only caller.
 */
object NativeEngine {
    init {
        System.loadLibrary("harbor")
    }

    /** Start the engine and the audio output (idempotent). Returns whether audio is running. */
    @JvmStatic external fun start(leadFrames: Int, lowLatency: Boolean): Boolean
    @JvmStatic external fun shutdown()
    @JvmStatic external fun setPaused(paused: Boolean)
    @JvmStatic external fun restartAudio(leadFrames: Int, lowLatency: Boolean)

    /** Boot a package. [sectors]: the sector store from [flashSectors] or a save, or null. */
    @JvmStatic external fun boot(pkg: ByteArray, sectors: ByteArray?, mhz: Int, autoClock: Boolean, syx: ByteArray?, jit: Boolean, id: Long)
    @JvmStatic external fun unload()

    @JvmStatic external fun key(id: Int, down: Boolean)
    @JvmStatic external fun enc(enc: Int, clicks: Int)
    @JvmStatic external fun pot(value: Int)
    @JvmStatic external fun analog(idx: Int, value: Int)
    @JvmStatic external fun velocity(us: Int)
    @JvmStatic external fun setClock(mhz: Int, auto: Boolean)

    /** A MIDI source opened; [remap]: channel 0..15 for its channel messages, or -1. */
    @JvmStatic external fun midiOpen(source: Int, remap: Int)
    @JvmStatic external fun midiRemap(source: Int, remap: Int)
    /** Bytes from a source with their `System.nanoTime` timestamp. True: not only real-time bytes. */
    @JvmStatic external fun midiData(source: Int, data: ByteArray, offset: Int, count: Int, timestamp: Long): Boolean
    @JvmStatic external fun midiClose(source: Int)
    @JvmStatic external fun panic()

    /** Copy the LCD (240x240 RGB565 LE) into [buf] (direct, 115200 bytes) unless its revision is [since]. Returns the revision now in [buf]. */
    @JvmStatic external fun lcd(buf: ByteBuffer, since: Long): Long
    @JvmStatic external fun leds(out: FloatArray)
    @JvmStatic external fun status(out: DoubleArray)
    @JvmStatic external fun error(): String
    @JvmStatic external fun flashSectors(): ByteArray?
    /** Blocks up to [timeoutMs]: the flash once no write is in progress, or null. */
    @JvmStatic external fun flush(timeoutMs: Int): ByteArray?

    /**
     * Record the firmware's output as a WAV into [fd] (writable and seekable; the engine takes it
     * and closes it). False if a recording is running already or the file failed.
     */
    @JvmStatic external fun recordStart(fd: Int): Boolean
    /** Blocks until the file is complete: [frames, dropped frames], or null if nothing was recording or it failed. */
    @JvmStatic external fun recordStop(): LongArray?

    /** "fm1" or "smk37"; throws IllegalArgumentException with the reason if the package doesn't unpack. */
    @JvmStatic external fun packageBoard(pkg: ByteArray): String
    @JvmStatic external fun saveEncode(name: String, pkg: ByteArray, sectors: ByteArray, includePackage: Boolean): ByteArray
    /** [name, package, sectors]; throws IllegalArgumentException with the reason. */
    @JvmStatic external fun saveDecode(data: ByteArray, fallback: ByteArray?): Array<Any>
    @JvmStatic external fun savePeekHash(data: ByteArray): String?
    @JvmStatic external fun jitAvailable(): Boolean

    const val LCD_SIZE = 240
    const val STATUS_FIELDS = 26
}

enum class RunState { Idle, Running, Halted, Failed }

/** One read of the engine's state (the order of `status` in native/harbor-jni/src/lib.rs). */
data class EngineStatus(
    val state: RunState = RunState.Idle,
    val bootId: Long = 0,
    val smk37: Boolean = false,
    val mhz: Int = 240,
    val autoClock: Boolean = true,
    val clockSteps: Int = 0,
    val jit: Boolean = false,
    /** how many times faster than real time the machine could run */
    val speed: Double = 0.0,
    /** frames the output found missing in the last second */
    val late: Long = 0,
    val starved: Long = 0,
    val peakL: Float = 0f,
    val peakR: Float = 0f,
    val clip: Boolean = false,
    val flashRevision: Long = 0,
    val flashBootId: Long = 0,
    val midiDropped: Long = 0,
    val leadFrames: Int = 0,
    val sampleRate: Int = 0,
    val framesPerBurst: Int = 0,
    val bufferFrames: Int = 0,
    val audioRunning: Boolean = false,
    val exclusive: Boolean = false,
    val xruns: Int = 0,
    val deviceId: Int = 0,
    /** MIDI bytes put into the emulated MIDI IN jack */
    val midiIn: Long = 0,
    /** frames recorded so far, or -1 when not recording */
    val recFrames: Long = -1,
) {
    /** Share of the audio time the emulation needs (1 = all of it). */
    val load: Double get() = if (speed > 0) 1.0 / speed else 0.0

    /** Estimated touch-to-sound latency: the device buffer plus the engine's lead. */
    val latencyMs: Double
        get() = if (sampleRate > 0) bufferFrames * 1000.0 / sampleRate + leadFrames * 1000.0 / 44_100 else 0.0

    companion object {
        fun from(a: DoubleArray) = EngineStatus(
            state = RunState.entries.getOrElse(a[0].toInt()) { RunState.Idle },
            bootId = a[1].toLong(),
            smk37 = a[2] != 0.0,
            mhz = a[3].toInt(),
            autoClock = a[4] != 0.0,
            clockSteps = a[5].toInt(),
            jit = a[6] != 0.0,
            speed = a[7],
            late = a[8].toLong(),
            starved = a[9].toLong(),
            peakL = a[10].toFloat(),
            peakR = a[11].toFloat(),
            clip = a[12] != 0.0,
            flashRevision = a[13].toLong(),
            flashBootId = a[14].toLong(),
            midiDropped = a[15].toLong(),
            leadFrames = a[16].toInt(),
            sampleRate = a[17].toInt(),
            framesPerBurst = a[18].toInt(),
            bufferFrames = a[19].toInt(),
            audioRunning = a[20] != 0.0,
            exclusive = a[21] != 0.0,
            xruns = a[22].toInt(),
            deviceId = a[23].toInt(),
            midiIn = a[24].toLong(),
            recFrames = a[25].toLong(),
        )
    }
}
