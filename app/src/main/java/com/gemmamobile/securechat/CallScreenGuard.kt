package com.gemmamobile.securechat

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Starts automatically when a phone call from an unknown number begins — Android binds this service
 * for every incoming call from a number that isn't in your contacts, even if the app is closed.
 * Requires the app to hold the "Caller ID & spam" (call screening) role, which is a normal
 * system prompt, not a restricted setting.
 *
 * It never blocks or rejects calls, and it can't hear the call; it only sees the number.
 */
class CallScreenGuard : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        // Always let the call through unchanged.
        respondToCall(details, CallResponse.Builder().build())

        if (details.callDirection != Call.Details.DIRECTION_INCOMING) return
        if (!ScamShield.callGuardOn(this)) return
        val number = details.handle?.schemeSpecificPart.orEmpty()

        if (ScamShield.isForeignNumber(number)) {
            ScamShield.show(this, ScamShield.Alert.FOREIGN_CALL, "Caller: $number")
        }
        showProtectionNotice(this, number)
        scheduleLongCallCheck(this, number)
    }

    companion object {
        private const val CHANNEL = "call_protection_v2"
        private const val NOTICE_ID = 77
        private const val LONG_CALL_MS = 10 * 60_000L

        /** Quiet notification for the duration of the call: shows the app is watching, one tap to Scam Check. */
        fun showProtectionNotice(ctx: Context, number: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.deleteNotificationChannel("call_protection")
                nm.createNotificationChannel(
                    // Visible in the status bar and as a banner, but silent (the call is already ringing).
                    NotificationChannel(CHANNEL, "Call protection", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        enableVibration(false)
                    }
                )
            }
            val scamCheck = PendingIntent.getActivity(
                ctx, 5,
                Intent(ctx, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SCAM_CHECK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setContentTitle("🛡 Call protection active")
                .setContentText("Unknown caller ${number.ifBlank { "" }}. Never share OTP, PIN or pay on a call.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Unknown caller $number.\nPolice/CBI never arrest or question anyone on a call. " +
                            "Never share OTP or UPI PIN. Unsure? Tap Scam Check."
                    )
                )
                .setContentIntent(scamCheck)
                .addAction(0, "Scam Check", scamCheck)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(30 * 60_000L)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            runCatching { nm.notify(NOTICE_ID, n) }
        }

        fun clearProtectionNotice(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTICE_ID)
        }

        /** Checks back in 10 minutes; if the call is still going, show the digital-arrest warning. */
        private fun scheduleLongCallCheck(ctx: Context, number: String) {
            val pi = PendingIntent.getBroadcast(
                ctx, 6,
                Intent(ctx, LongCallCheckReceiver::class.java).putExtra("number", number),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + LONG_CALL_MS, pi,
            )
        }

        fun isInCall(ctx: Context): Boolean {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                runCatching { return ctx.getSystemService(TelecomManager::class.java).isInCall }
            }
            return ctx.getSystemService(AudioManager::class.java).mode == AudioManager.MODE_IN_CALL
        }
    }
}

/** Fired by the 10-minute alarm set when an unknown call started. Not exported. */
class LongCallCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ScamShield.callGuardOn(context)) return
        if (CallScreenGuard.isInCall(context)) {
            val number = intent.getStringExtra("number").orEmpty()
            ScamShield.show(context, ScamShield.Alert.LONG_UNKNOWN_CALL, "Caller: $number · 10+ min")
        } else {
            CallScreenGuard.clearProtectionNotice(context)
        }
    }
}
