package com.markwhite.harbor.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import com.markwhite.harbor.data.BufferMode
import com.markwhite.harbor.data.Catalog
import com.markwhite.harbor.data.FirmwareEntry
import com.markwhite.harbor.data.FirmwareStore
import com.markwhite.harbor.data.InstalledPackage
import com.markwhite.harbor.data.PackageException
import com.markwhite.harbor.data.Recordings
import com.markwhite.harbor.data.SaveStore
import com.markwhite.harbor.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/** What the Play screen shows around the panel (ui-spec §6.2.5). */
sealed interface Phase {
    data object Empty : Phase
    data class Downloading(val name: String, val got: Long, val total: Long) : Phase
    data class DownloadFailed(val entry: FirmwareEntry, val message: String) : Phase
    data class NeedsFile(val entry: FirmwareEntry) : Phase
    data object Booting : Phase
    data object Running : Phase
    data object Halted : Phase
    data class Failed(val message: String) : Phase
    data class Paused(val reason: String) : Phase
}

sealed interface SaveState {
    data object NotSaved : SaveState
    data object Saving : SaveState
    data class Saved(val at: Long, val sectors: Int) : SaveState
    data class Failed(val message: String) : SaveState
}

sealed interface HarborEvent {
    data class ClockReduced(val name: String, val mhz: Int) : HarborEvent
    data class Message(val text: String) : HarborEvent
    /** A recording was saved to [Recordings.FOLDER_NAME]. */
    data class Recorded(val name: String, val uri: android.net.Uri, val seconds: Long) : HarborEvent
}

/** A recording in progress, written into the shared Music folder as it plays. */
data class Recording(val name: String, val uri: android.net.Uri)

/** The firmware in the emulator now. */
data class Docked(val pkg: InstalledPackage, val entry: FirmwareEntry?)

/**
 * The app's one emulator: docking firmwares, the automatic clock's memory, flash persistence,
 * audio focus and routing. Thread-safe; UI reads the flows and polls [pollLcd]/[pollLeds].
 */
@androidx.compose.runtime.Stable
class Harbor(
    private val context: Context,
    val catalog: Catalog,
    val store: FirmwareStore,
    val saves: SaveStore,
    val settings: Settings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bootIds = AtomicLong(0)
    private val statusBuf = DoubleArray(NativeEngine.STATUS_FIELDS)

    private val _phase = MutableStateFlow<Phase>(Phase.Empty)
    val phase: StateFlow<Phase> = _phase
    private val _docked = MutableStateFlow<Docked?>(null)
    val docked: StateFlow<Docked?> = _docked
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status
    private val _save = MutableStateFlow<SaveState>(SaveState.NotSaved)
    val save: StateFlow<SaveState> = _save
    private val _events = MutableSharedFlow<HarborEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<HarborEvent> = _events
    /** The MASTER pot, 0..1023 */
    private val _master = MutableStateFlow(512)
    val master: StateFlow<Int> = _master
    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording
    private val recordings = Recordings(context)
    private val recordLock = Mutex()

    /**
     * Harbor is in front (the process lifecycle's resumed state). Behind another app, a share sheet
     * or the shade, Android gives it less CPU: slowness then says nothing about this device.
     */
    @Volatile var onScreen = true

    @Volatile private var started = false
    @Volatile private var currentBoot = 0L
    private var lastFlashRevision = -1L
    private var lastClockSteps = 0
    private var bootStartedMs = 0L
    private var runningSinceMs = 0L
    private var lastState = RunState.Idle
    private var lastHealthMs = 0L
    private var lastStarved = -1L
    private var stalledSince = 0L
    private var stuckLogged = false
    private var downloadJob: Job? = null
    private var paused = false
    private var lastAudioRetry = 0L
    private var transientLoss = false

    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> pause("Paused · another app is playing")
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { transientLoss = true; pause("Paused") }
                AudioManager.AUDIOFOCUS_GAIN -> if (paused && transientLoss) { transientLoss = false; resume() }
            }
        }
        .setWillPauseWhenDucked(false)
        .build()

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause("Paused · headphones disconnected")
        }
    }

    private fun leadFrames() = if (settings.bufferMode.value == BufferMode.LowLatency) 768 else 2048

    /** Start the engine and audio (idempotent), and the status loop. */
    @Synchronized
    fun ensureStarted() {
        if (started) return
        started = true
        NativeEngine.start(leadFrames(), settings.bufferMode.value == BufferMode.LowLatency)
        audio.requestAudioFocus(focus)
        context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        scope.launch { statusLoop() }
    }

    /** Resume the firmware played last, if it is still kept (on launch). */
    fun resumeLast() {
        if (_docked.value != null) return
        val sha = settings.lastSha ?: return
        val pkg = store.bySha(sha) ?: return
        boot(pkg)
    }

    // ------------------------------------------------------------------------------- docking

    /**
     * Play a catalog firmware: from the kept package, else download it first. [update]: download
     * the catalog's package even though an older one is kept (it gets a save of its own).
     */
    fun play(entry: FirmwareEntry, update: Boolean = false) {
        ensureStarted()
        if (!update) store.latestFor(entry.id)?.let { return boot(it) }
        if (entry.byo || entry.`package` == null) {
            _phase.value = Phase.NeedsFile(entry)
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _phase.value = Phase.Downloading(entry.title, 0, -1)
            try {
                val pkg = store.download(entry) { got, total -> _phase.value = Phase.Downloading(entry.title, got, total) }
                boot(pkg)
            } catch (e: PackageException) {
                _phase.value = Phase.DownloadFailed(entry, e.message ?: "download failed")
            } catch (e: java.io.IOException) {
                _phase.value = Phase.DownloadFailed(entry, "Couldn't download ${entry.name}: ${e.message ?: "no connection"}")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _phase.value = if (_docked.value != null) Phase.Running else Phase.Empty
    }

    /** Keep and play a package the user opened. [forEntry]: a bring-your-own catalog entry it is for. */
    suspend fun openPackage(bytes: ByteArray, name: String, forEntry: FirmwareEntry? = null): Result<InstalledPackage> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = store.add(bytes, forEntry?.id ?: "local", forEntry?.name ?: name.removeSuffix(".fwsc"), forEntry?.version ?: "")
                ensureStarted()
                boot(pkg)
                pkg
            }
        }

    /**
     * Boot a kept package with its saved flash, at its remembered clock. [flushFirst]: store the
     * running firmware's flash before replacing it (false when its save was just replaced).
     */
    fun boot(pkg: InstalledPackage, syx: ByteArray? = null, flushFirst: Boolean = true) {
        ensureStarted()
        scope.launch {
            if (flushFirst) flushSave()
            val bytes = withContext(Dispatchers.IO) { store.read(pkg.sha) }
            val sectors = withContext(Dispatchers.IO) { saves.read(pkg.sha) }
            val id = bootIds.incrementAndGet()
            currentBoot = id
            lastFlashRevision = -1
            lastClockSteps = 0
            _docked.value = Docked(pkg, catalog.byId(pkg.catalogId))
            _master.value = settings.master(pkg.sha)
            _save.value = if (sectors != null) SaveState.Saved(saves.savedAt(pkg.sha), saves.sectorCount(pkg.sha)) else SaveState.NotSaved
            _phase.value = Phase.Booting
            settings.lastSha = pkg.sha
            NativeEngine.pot(_master.value)
            val mhz = settings.clock(pkg.sha)
            val auto = settings.autoClock(pkg.sha)
            bootStartedMs = System.currentTimeMillis()
            runningSinceMs = 0
            lastState = RunState.Idle
            EventLog.i("boot #$id ${pkg.title} (${pkg.sha.take(12)}): $mhz MHz${if (auto) " auto" else ""}, " +
                "${sectors?.let { it.size / 4100 } ?: 0} saved sectors${if (syx != null) ", with .syx" else ""}")
            NativeEngine.boot(bytes, sectors, mhz, auto, syx, true, id)
        }
    }

    /** Restart the docked firmware from its saved flash. */
    fun restart() {
        _docked.value?.let { boot(it.pkg) }
    }

    // --------------------------------------------------------------------------------- panel

    fun key(id: Int, down: Boolean) = NativeEngine.key(id, down)
    fun enc(enc: Int, clicks: Int) = NativeEngine.enc(enc, clicks)

    fun setMaster(v: Int) {
        val value = v.coerceIn(0, 1023)
        _master.value = value
        NativeEngine.pot(value)
        _docked.value?.let { settings.setMaster(it.pkg.sha, value) }
    }

    private val lcdBuf: ByteBuffer = ByteBuffer.allocateDirect(240 * 240 * 2).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Copy the screen into [bitmap] (240x240 RGB_565) if its revision isn't [since] (the bitmap's
     * last copy; -1 for a new bitmap). Returns the revision the bitmap now shows.
     */
    @Synchronized
    fun pollLcd(bitmap: android.graphics.Bitmap, since: Long): Long {
        if (!started) return since
        val rev = NativeEngine.lcd(lcdBuf, since)
        if (rev == since) return since
        lcdBuf.rewind()
        bitmap.copyPixelsFromBuffer(lcdBuf)
        lcdBuf.rewind()
        return rev
    }

    fun pollLeds(out: FloatArray) {
        if (started) NativeEngine.leds(out)
    }

    // -------------------------------------------------------------------------------- clock

    /** Set the clock by hand ([auto] false), or go back to the automatic clock from 240 MHz. */
    fun setClock(mhz: Int, auto: Boolean) {
        val d = _docked.value ?: return
        val target = if (auto) 240 else mhz
        settings.setAutoClock(d.pkg.sha, auto)
        settings.setClock(d.pkg.sha, target)
        // a choice made by hand starts the automatic clock's memory afresh
        settings.setClockSeen(d.pkg.sha, 0)
        NativeEngine.setClock(target, auto)
        _events.tryEmit(HarborEvent.Message("Restarted at $target MHz. Saved flash kept."))
    }

    fun setBufferMode(mode: BufferMode) {
        settings.setBufferMode(mode)
        if (started) NativeEngine.restartAudio(leadFrames(), mode == BufferMode.LowLatency)
    }

    // -------------------------------------------------------------------------------- audio

    fun pause(reason: String = "Paused") {
        if (!started || paused) return
        EventLog.i("pause: $reason")
        paused = true
        NativeEngine.setPaused(true)
        if (_docked.value != null) _phase.value = Phase.Paused(reason)
        scope.launch { flushSave() }
    }

    fun resume() {
        if (!started) return
        if (paused) EventLog.i("resume")
        paused = false
        audio.requestAudioFocus(focus)
        NativeEngine.setPaused(false)
        if (_phase.value is Phase.Paused) _phase.value = Phase.Running
    }

    val isPaused: Boolean get() = paused

    /** The output device changed (routing callback): reopen the stream on the new one. */
    fun audioDeviceChanged() {
        if (started && !paused) NativeEngine.restartAudio(leadFrames(), settings.bufferMode.value == BufferMode.LowLatency)
    }

    // ---------------------------------------------------------------------------- recording

    /** Start recording what the firmware plays (MASTER included) into Music/Harbor. */
    fun startRecording() {
        ensureStarted()
        scope.launch(Dispatchers.IO) {
            recordLock.withLock {
                if (_recording.value != null) return@withLock
                val firmware = _docked.value?.let { it.entry?.name ?: it.pkg.name } ?: "Harbor"
                val name = recordings.nameFor(firmware)
                val (uri, fd) = try {
                    recordings.create(name, firmware)
                } catch (e: Exception) {
                    EventLog.w("couldn't start a recording: ${e.message}")
                    _events.tryEmit(HarborEvent.Message("Couldn't start a recording: ${e.message ?: "no space"}"))
                    return@withLock
                }
                if (NativeEngine.recordStart(fd.detachFd())) {
                    EventLog.i("recording \"$name\"")
                    _recording.value = Recording(name, uri)
                } else {
                    recordings.delete(uri)
                    _events.tryEmit(HarborEvent.Message("Couldn't start a recording."))
                }
            }
        }
    }

    /** Finish the recording and make it visible to other apps; an empty one is removed. */
    fun stopRecording() {
        if (_recording.value == null) return
        scope.launch(Dispatchers.IO) {
            recordLock.withLock {
                val r = _recording.value ?: return@withLock
                _recording.value = null
                val result = NativeEngine.recordStop()
                val frames = result?.get(0) ?: 0
                if (result != null && result[1] > 0) EventLog.w("recording lost ${result[1]} frames: the writer fell behind")
                when {
                    result == null -> {
                        recordings.delete(r.uri)
                        _events.tryEmit(HarborEvent.Message("The recording couldn't be saved."))
                    }
                    // nothing played (paused all along, or stopped at once)
                    frames < 44_100 / 10 -> {
                        recordings.delete(r.uri)
                        _events.tryEmit(HarborEvent.Message("Nothing was recorded."))
                    }
                    else -> {
                        recordings.publish(r.uri)
                        EventLog.i("recorded \"${r.name}\": ${frames / 44_100} s")
                        _events.tryEmit(HarborEvent.Recorded(r.name, r.uri, frames / 44_100))
                    }
                }
            }
        }
    }

    val isRecording: Boolean get() = _recording.value != null

    /** Finish recordings a killed process left pending (at launch, before any recording). */
    fun recoverRecordings() {
        scope.launch(Dispatchers.IO) { recordLock.withLock { if (_recording.value == null) recordings.recoverUnfinished() } }
    }

    // -------------------------------------------------------------------------------- saves

    /** Wait for the firmware to finish writing, then store its flash. */
    suspend fun flushSave(): Boolean = withContext(Dispatchers.IO) {
        val d = _docked.value ?: return@withContext false
        if (!started || currentBoot == 0L) return@withContext false
        val sectors = NativeEngine.flush(3000) ?: return@withContext false
        writeSave(d.pkg.sha, sectors)
    }

    private fun writeSave(sha: String, sectors: ByteArray): Boolean = try {
        _save.value = SaveState.Saving
        if (sectors.isEmpty()) saves.reset(sha) else saves.write(sha, sectors)
        _save.value = if (sectors.isEmpty()) SaveState.NotSaved
        else SaveState.Saved(System.currentTimeMillis(), sectors.size / SaveStore.RECORD)
        true
    } catch (e: Exception) {
        Log.e(TAG, "save failed", e)
        EventLog.w("save failed: ${e.message}")
        _save.value = SaveState.Failed(e.message ?: "couldn't write the save")
        false
    }

    /** Forget a firmware's saved flash and restart it pristine. */
    fun resetSave(pkg: InstalledPackage) {
        saves.reset(pkg.sha)
        if (_docked.value?.pkg?.sha == pkg.sha) boot(pkg, flushFirst = false)
    }

    /** A `.fm1save` of the docked firmware (no package for bring-your-own firmware). */
    suspend fun exportSave(): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val d = _docked.value ?: throw IllegalStateException("nothing is docked")
        flushSave()
        val sectors = saves.read(d.pkg.sha) ?: ByteArray(0)
        val includePackage = d.entry?.byo != true
        val name = d.pkg.title
        val file = name.replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".fm1save"
        file to NativeEngine.saveEncode(name, store.read(d.pkg.sha), sectors, includePackage)
    }

    data class ImportSummary(val name: String, val sectors: Int, val pkg: ByteArray, val sectorBytes: ByteArray, val known: InstalledPackage?)

    /** Read a `.fm1save` (checked, not applied). Bring-your-own saves need their package kept already. */
    suspend fun readSave(bytes: ByteArray): ImportSummary = withContext(Dispatchers.IO) {
        val fallback = NativeEngine.savePeekHash(bytes)?.let { store.bySha(it) }?.let { store.read(it.sha) }
        val parts = NativeEngine.saveDecode(bytes, fallback)
        val pkg = parts[1] as ByteArray
        val sectors = parts[2] as ByteArray
        ImportSummary(parts[0] as String, sectors.size / SaveStore.RECORD, pkg, sectors, store.bySha(FirmwareStore.sha256(pkg)))
    }

    /** Replace the save of the summary's firmware and boot it. */
    suspend fun applySave(s: ImportSummary) = withContext(Dispatchers.IO) {
        flushSave()
        val pkg = s.known ?: store.add(s.pkg, "local", s.name)
        if (s.sectorBytes.isEmpty()) saves.reset(pkg.sha) else saves.write(pkg.sha, s.sectorBytes)
        boot(pkg, flushFirst = false)
    }

    /** Load a 32-voice DX7 bank into the stock firmware (slots 1-32); kept in its save. */
    fun loadSyx(bytes: ByteArray) {
        val d = _docked.value ?: return
        scope.launch {
            flushSave()
            boot(d.pkg, bytes, flushFirst = false)
        }
    }

    fun unload() {
        scope.launch {
            flushSave()
            NativeEngine.unload()
            currentBoot = 0
            _docked.value = null
            _phase.value = Phase.Empty
        }
    }

    // ------------------------------------------------------------------------- status loop

    /**
     * A health line every 30 s, and at once when something looks wrong: output dropping, or a
     * machine that says it's running but has made no progress for 3 s (the "bad state" kind).
     */
    private fun health(s: EngineStatus, nowMs: Long) {
        if (paused || s.state != RunState.Running) { stalledSince = 0; return }
        // the first seconds of a boot run slowly while the JIT compiles: silence, not dropouts
        val settling = nowMs - runningSinceMs < 3000
        val dropped = !settling && lastStarved >= 0 && s.starved > lastStarved
        lastStarved = s.starved
        if (s.speed <= 0.0 && nowMs - runningSinceMs > 3000) {
            if (stalledSince == 0L) stalledSince = nowMs
            if (nowMs - stalledSince >= 3000 && !stuckLogged) {
                stuckLogged = true
                EventLog.w("no progress for 3 s while running (speed 0): the machine looks stuck")
            }
        } else { stalledSince = 0; stuckLogged = false }
        val periodic = !settling && nowMs - lastHealthMs >= 30_000
        if (periodic || (dropped && nowMs - lastHealthMs >= 1000)) {
            lastHealthMs = nowMs
            val line = "health: ${s.mhz} MHz, speed ${"%.2f".format(s.speed)}x, load ${(s.load * 100).toInt()}%, " +
                "late ${s.late}, dropped ${s.starved}, xruns ${s.xruns}, lead ${s.leadFrames}, MIDI in ${s.midiIn}"
            if (dropped) EventLog.w(line) else EventLog.i(line)
        }
    }

    private suspend fun statusLoop() {
        while (scope.isActive) {
            NativeEngine.status(statusBuf)
            val s = EngineStatus.from(statusBuf)
            _status.value = s
            val d = _docked.value
            // a stream closed by a route change (headphones, USB audio): reopen on the new device
            val nowMs = System.currentTimeMillis()
            if (d != null && !paused && !s.audioRunning && nowMs - lastAudioRetry > 1000) {
                lastAudioRetry = nowMs
                EventLog.w("audio output closed (route change or error): reopening")
                NativeEngine.start(leadFrames(), settings.bufferMode.value == BufferMode.LowLatency)
            }
            if (d != null && s.bootId == currentBoot) {
                if (s.state != lastState) {
                    lastState = s.state
                    when (s.state) {
                        RunState.Running -> {
                            runningSinceMs = nowMs
                            EventLog.i("boot #${s.bootId} running after ${nowMs - bootStartedMs} ms: ${s.mhz} MHz, JIT ${if (s.jit) "on" else "off"}")
                        }
                        RunState.Halted -> EventLog.w("boot #${s.bootId} halted: the emulated CPU stopped (${s.mhz} MHz, speed ${"%.2f".format(s.speed)}x)")
                        RunState.Failed -> EventLog.w("boot #${s.bootId} failed: ${NativeEngine.error()}")
                        RunState.Idle -> {}
                    }
                }
                when (s.state) {
                    RunState.Running -> if (_phase.value == Phase.Booting) _phase.value = Phase.Running
                    RunState.Halted -> _phase.value = Phase.Halted
                    RunState.Failed -> _phase.value = Phase.Failed(NativeEngine.error())
                    RunState.Idle -> {}
                }
                health(s, nowMs)
                // a long session that never stepped down: the earlier step was a fluke
                if (s.state == RunState.Running && s.clockSteps == 0 && runningSinceMs > 0 && nowMs - runningSinceMs > 120_000 &&
                    settings.clockSeen(d.pkg.sha) != 0) {
                    EventLog.i("2 minutes at ${s.mhz} MHz without a step: forgetting the earlier session's ${settings.clockSeen(d.pkg.sha)} MHz")
                    settings.setClockSeen(d.pkg.sha, 0)
                }
                // the automatic clock stepped down: trust it for this device once a second
                // session needs it too, so one bad moment doesn't slow the firmware for good
                if (s.clockSteps > lastClockSteps && !onScreen) {
                    // the step keeps the background smooth, but isn't this firmware's clock on this device
                    lastClockSteps = s.clockSteps
                    EventLog.w("clock stepped down to ${s.mhz} MHz while Harbor wasn't in front (speed ${"%.2f".format(s.speed)}x): not remembered")
                } else if (s.clockSteps > lastClockSteps) {
                    lastClockSteps = s.clockSteps
                    val seen = settings.clockSeen(d.pkg.sha)
                    if (seen != 0 && seen <= s.mhz) settings.setClock(d.pkg.sha, s.mhz)
                    else settings.setClockSeen(d.pkg.sha, s.mhz)
                    EventLog.w("clock stepped down to ${s.mhz} MHz (speed ${"%.2f".format(s.speed)}x, ${s.late} frames late; " +
                        if (seen != 0 && seen <= s.mhz) "second session: now its boot clock)" else "first time: not remembered yet)")
                    if (!settings.clockNoticeShown(d.pkg.sha)) {
                        settings.setClockNoticeShown(d.pkg.sha)
                        _events.tryEmit(HarborEvent.ClockReduced(d.entry?.name ?: d.pkg.name, s.mhz))
                    }
                }
                // the firmware wrote flash and has gone quiet: keep it
                if (s.flashBootId == currentBoot && s.flashRevision != lastFlashRevision) {
                    val first = lastFlashRevision == -1L
                    lastFlashRevision = s.flashRevision
                    if (!first) {
                        val sectors = NativeEngine.flashSectors()
                        if (sectors != null) withContext(Dispatchers.IO) { writeSave(d.pkg.sha, sectors) }
                    }
                }
            }
            delay(100)
        }
    }

    companion object {
        private const val TAG = "Harbor"
    }
}
