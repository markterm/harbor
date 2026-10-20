package com.markwhite.harbor

import kotlinx.coroutines.launch
import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.markwhite.harbor.data.Catalog
import com.markwhite.harbor.data.FirmwareStore
import com.markwhite.harbor.data.SaveStore
import com.markwhite.harbor.data.Settings
import com.markwhite.harbor.engine.Harbor
import com.markwhite.harbor.midi.HarborMidiService
import com.markwhite.harbor.midi.MidiHub
import com.markwhite.harbor.service.PlaybackService

/** The process's singletons: one emulator, its stores and the MIDI hub. */
class HarborApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var catalog: Catalog
        private set
    lateinit var store: FirmwareStore
        private set
    lateinit var saves: SaveStore
        private set
    lateinit var harbor: Harbor
        private set
    lateinit var midi: MidiHub
        private set

    /** Whether an activity of ours is started. */
    @Volatile var inForeground = false
        private set
    /** Paused because the app left the screen (not by the user or a lost output). */
    private var pausedForBackground = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        catalog = Catalog(this)
        store = FirmwareStore(this)
        saves = SaveStore(this)
        harbor = Harbor(this, catalog, store, saves, settings)
        midi = MidiHub(this, settings)
        harbor.recoverRecordings()
        // keep the firmware list current: packages move on the hub as authors release
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch { catalog.refreshIfStale() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                midi.start()
                if (pausedForBackground) {
                    pausedForBackground = false
                    harbor.resume()
                }
            }

            // on screen and in front: a share sheet or the shade over Harbor counts as not
            override fun onResume(owner: LifecycleOwner) { harbor.onScreen = true }
            override fun onPause(owner: LifecycleOwner) { harbor.onScreen = false }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                // a recording carries on in the background: leaving the app shouldn't cut the take
                val keep = settings.keepPlayingInBackground.value || HarborMidiService.clients.value > 0 || harbor.isRecording
                if (harbor.docked.value == null) return
                if (keep) {
                    PlaybackService.startBackground(this@HarborApp)
                } else {
                    // (a service a background recording started stays no longer than the recording)
                    PlaybackService.stop(this@HarborApp)
                    if (!harbor.isPaused) {
                        pausedForBackground = true
                        harbor.pause()
                    }
                }
            }
        })
    }
}
