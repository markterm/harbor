package com.markwhite.harbor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.markwhite.harbor.engine.EventLog
import com.markwhite.harbor.engine.NativeEngine
import java.io.File

/**
 * Profile builds only: records the firmware's output like the record button, but without the
 * button's state on screen, for filming a demo. The file lands in the app's external files
 * (Android/data/com.markwhite.harbor/files/demo), which adb can pull.
 *
 *   adb shell am broadcast -n com.markwhite.harbor/.DemoCapture -a com.markwhite.harbor.demo.CAPTURE --es name take1
 *   adb shell am broadcast -n com.markwhite.harbor/.DemoCapture -a com.markwhite.harbor.demo.CAPTURE --ez stop true
 *
 * Each start writes its time since boot (/proc/uptime's clock) beside the WAV (`name.wav.t`), to
 * line the audio up with a screen recording.
 */
class DemoCapture : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.getBooleanExtra("stop", false)) {
                    val r = NativeEngine.recordStop()
                    EventLog.i("demo capture stopped: ${r?.getOrNull(0) ?: -1} frames, ${r?.getOrNull(1) ?: -1} dropped")
                } else {
                    val name = intent.getStringExtra("name") ?: "take"
                    val dir = File(context.getExternalFilesDir(null), "demo").apply { mkdirs() }
                    val file = File(dir, "$name.wav")
                    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
                    val t = SystemClock.elapsedRealtime()
                    val ok = NativeEngine.recordStart(fd.detachFd())
                    File(dir, "$name.wav.t").writeText("$t\n")
                    EventLog.i("demo capture $name: ${if (ok) "started" else "refused"} at boot time $t ms")
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
