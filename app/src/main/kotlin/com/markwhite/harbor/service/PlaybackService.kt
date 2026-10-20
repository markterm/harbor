package com.markwhite.harbor.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.markwhite.harbor.HarborApp
import com.markwhite.harbor.MainActivity
import com.markwhite.harbor.R
import com.markwhite.harbor.midi.HarborMidiService

/**
 * Keeps Harbor playing while it isn't on screen: when another app plays it through its MIDI
 * device, or when "Keep playing in background" is on (ui-spec §11.2). The engine itself lives in
 * the process (HarborApp); this service only keeps the process in the foreground.
 */
class PlaybackService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopLater = Runnable { stopNow() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HarborApp
        when (intent?.action) {
            ACTION_STOP -> {
                app.harbor.stopRecording()
                app.harbor.pause()
                stopNow()
                return START_NOT_STICKY
            }
            ACTION_CLIENTS_GONE -> {
                // the last app disconnected: stop after 30 s unless asked to keep playing
                if (!app.settings.keepPlayingInBackground.value && !app.inForeground) handler.postDelayed(stopLater, 30_000)
            }
            else -> handler.removeCallbacks(stopLater)
        }
        app.harbor.ensureStarted()
        app.harbor.resumeLast()
        if (app.harbor.isPaused) app.harbor.resume()
        startForeground(NOTIFICATION_ID, notification(intent?.action == ACTION_CLIENT || HarborMidiService.clients.value > 0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private fun stopNow() {
        handler.removeCallbacks(stopLater)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(forClient: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW))
        val app = application as HarborApp
        val name = app.harbor.docked.value?.let { it.entry?.name ?: it.pkg.name } ?: "the FM-1"
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Harbor")
            .setContentText(when {
                app.harbor.isRecording -> "Harbor is recording $name."
                forClient -> "Harbor is playing $name for another app."
                else -> "Harbor is playing $name."
            })
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .addAction(Notification.Action.Builder(null, "Open", open).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CLIENT = "client"
        private const val ACTION_CLIENTS_GONE = "clientsGone"
        private const val ACTION_STOP = "stop"
        private const val ACTION_BACKGROUND = "background"

        private fun start(context: Context, action: String) {
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java).setAction(action))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // a background start Android refused: the bound MIDI service keeps the process alive meanwhile
                Log.w(TAG, "foreground service not allowed now", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "service start refused", e)
            }
        }

        fun startForClient(context: Context) = start(context, ACTION_CLIENT)
        fun startBackground(context: Context) = start(context, ACTION_BACKGROUND)

        fun clientsGone(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_CLIENTS_GONE)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
