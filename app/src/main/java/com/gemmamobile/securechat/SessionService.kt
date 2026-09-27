package com.gemmamobile.securechat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Foreground service that keeps a live meeting (microphone) or call-recording analysis running
 * while the app is in the background or the screen is off.
 *
 * It holds no data itself; the work happens in ChatViewModel. The notification never shows any
 * transcript text, and it offers a Stop button.
 */
class SessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_REQUEST -> {
                stopRequests.tryEmit(Unit)
                return START_NOT_STICKY
            }
        }
        val mic = intent?.getBooleanExtra(EXTRA_MIC, false) == true
        val type = if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, mic, ""), type)

        // Keep the CPU running with the screen off so recording and transcription don't pause.
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GemmaSecureChat:session")
                .apply { acquire(MAX_WAKE_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from Recents: the work (in the activity's ViewModel) is gone, so stop.
        stopRequests.tryEmit(Unit)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP_REQUEST = "stop"
        private const val EXTRA_MIC = "mic"
        private const val MAX_WAKE_MS = 4 * 60 * 60 * 1000L

        private val stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        /** Emits when the user taps Stop in the notification. */
        val stopEvents = stopRequests.asSharedFlow()

        @Volatile private var running = false

        fun start(context: Context, mic: Boolean) {
            running = true
            ContextCompat.startForegroundService(
                context, Intent(context, SessionService::class.java).putExtra(EXTRA_MIC, mic)
            )
        }

        /** Updates the notification's status line (elapsed time / progress). Never pass transcript text. */
        fun update(context: Context, mic: Boolean, text: String) {
            if (!running) return
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(context, mic, text))
            }
        }

        fun stop(context: Context) {
            running = false
            context.stopService(Intent(context, SessionService::class.java))
        }

        private fun buildNotification(ctx: Context, mic: Boolean, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Meeting & call notes", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                ctx, 1,
                Intent(ctx, SessionService::class.java).setAction(ACTION_STOP_REQUEST),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(if (mic) "Recording meeting" else "Analyzing call recording")
                .setContentText(text.ifBlank { "Running on-device. Tap to open." })
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .addAction(0, if (mic) "Stop recording" else "Stop", stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
