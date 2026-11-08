package app.photoframe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/** Keeps the server, wake word, HA connection and media alive while the activity isn't visible. */
class FrameService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Photo frame", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Photo frame running")
            .setSmallIcon(R.drawable.ic_frame)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // Android only allows the microphone type while the frame is visible, and
        // Android 15 blocks some types when started from boot. Try the richest type
        // first and fall back; MainActivity re-calls this on resume to upgrade.
        val media = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val types = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                add(media or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            }
            add(media)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
        val started = types.any { type ->
            try { startForeground(1, n, type); true } catch (e: Exception) {
                Log.w("FrameService", "startForeground(type=$type) refused: ${e.message}"); false
            }
        }
        if (!started) {
            // Every type refused: stop instead of letting Android kill the app for
            // not calling startForeground in time. The activity restarts us.
            Log.w("FrameService", "foreground not allowed right now; waiting for the frame to be visible")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            // Partial lock keeps wake word + server running when the screen is off.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "photoframe:service").apply { acquire() }
        }
        FrameApp.graph.start()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.release(); wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "frame"
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, FrameService::class.java))
            } catch (e: Exception) {
                // Background start not allowed (e.g. Android 12+ restrictions); the
                // activity starts us when it comes to the front.
                Log.w("FrameService", "could not start service: ${e.message}")
            }
        }
    }
}
