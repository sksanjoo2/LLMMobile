package com.gemmamobile.securechat

import android.app.Notification
import android.os.Build
import android.provider.Telephony
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches for ongoing-call notifications (phone dialer, WhatsApp, Telegram, Signal, …) to warn about
 * risky calls in real time: foreign numbers, video calls from unknown numbers, and long calls with
 * unknown numbers — the pattern of "digital arrest" scams.
 *
 * Only notifications in the CALL category are looked at; every other notification is ignored.
 * Call info is kept in memory only while the call lasts.
 */
class CallGuardService : NotificationListenerService() {

    private data class CallInfo(val caller: String, val video: Boolean, val startedAt: Long)

    private val main = Handler(Looper.getMainLooper())
    private val longCallChecks = ConcurrentHashMap<String, Runnable>()
    private val callInfo = ConcurrentHashMap<String, CallInfo>()

    override fun onListenerConnected() {
        // Pick up a call that was already in progress when the listener (re)connected.
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Never look at conversations (chats / message threads), even if the user left that type on.
        if (isConversation(sbn)) return
        if (isSmsAppMessage(sbn)) {
            checkOtpDuringCall(sbn)
            return
        }
        if (!isOngoingCall(sbn)) return
        val key = sbn.key
        if (activeCalls.containsKey(key)) return // same call, notification just updated

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val video = (title + " " + text).contains("video", ignoreCase = true)
        activeCalls[key] = sbn.packageName
        val call = CallInfo(title, video, System.currentTimeMillis())
        callInfo[key] = call

        if (!ScamShield.callGuardOn(this)) return
        val unknown = ScamShield.isRawNumber(title)
        when {
            ScamShield.isForeignNumber(title) -> ScamShield.show(this, ScamShield.Alert.FOREIGN_CALL, "Caller: $title")
            unknown && video -> ScamShield.show(this, ScamShield.Alert.UNKNOWN_VIDEO_CALL, "Caller: $title")
        }
        if (unknown) {
            val check = Runnable {
                if (activeCalls.containsKey(key) && ScamShield.callGuardOn(this)) {
                    val mins = (System.currentTimeMillis() - call.startedAt) / 60_000
                    ScamShield.show(this, ScamShield.Alert.LONG_UNKNOWN_CALL, "Caller: ${call.caller} · $mins min")
                }
            }
            longCallChecks[key] = check
            main.postDelayed(check, LONG_CALL_MS)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = activeCalls.remove(sbn.key)
        val call = callInfo.remove(sbn.key)
        if (pkg != null) {
            lastCallEndedAt = System.currentTimeMillis()
            // Unknown caller, talked for 30 s+ → offer an after-call scam check-up.
            if (call != null && ScamShield.callGuardOn(this) && ScamShield.isRawNumber(call.caller) &&
                lastCallEndedAt - call.startedAt >= 30_000
            ) {
                ScamShield.afterCallCheckup(this, call.caller, phoneCall = pkg !in VOIP_APPS)
            }
            if (activeCalls.isEmpty()) CallScreenGuard.clearProtectionNotice(this)
            longCallChecks.remove(sbn.key)?.let(main::removeCallbacks)
        }
    }

    override fun onListenerDisconnected() {
        activeCalls.clear()
        callInfo.clear()
        longCallChecks.values.forEach(main::removeCallbacks)
        longCallChecks.clear()
    }

    /**
     * OTP-during-call alarm without any SMS permission: looks at the notification the SMS app posts.
     * On Android 15+ the system hides OTP text from notification-access apps; a hidden-content SMS
     * notification is itself the signal, because Android only hides OTP-like messages.
     */
    private fun checkOtpDuringCall(sbn: StatusBarNotification) {
        if (!ScamShield.otpGuardOn(this) || !ScamShield.inOrJustAfterCall(this)) return
        val extras = sbn.notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val hiddenOtp = Build.VERSION.SDK_INT >= 35 &&
            (text.isBlank() || text.contains("sensitive", ignoreCase = true) || text.contains("hidden", ignoreCase = true))
        if (ScamShield.looksLikeOtp(text) || hiddenOtp) {
            ScamShield.show(this, ScamShield.Alert.OTP_DURING_CALL, sender.takeIf { it.isNotBlank() }?.let { "Message from: $it" })
        }
    }

    private fun isConversation(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        val known = runCatching { currentRanking?.getRanking(sbn.key, ranking) == true }.getOrDefault(false)
        if (known && ranking.isConversation) return true
        // Fallback: messaging-style notifications are conversations.
        return sbn.notification.extras.containsKey(Notification.EXTRA_MESSAGES)
    }

    private fun isSmsAppMessage(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (sbn.isOngoing) return false
        val defaultSms = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
        return pkg == defaultSms || pkg in SMS_APPS
    }

    private fun isOngoingCall(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.category != Notification.CATEGORY_CALL) return false
        if (sbn.packageName == packageName) return false
        // In-progress calls are ongoing (non-dismissable); ringing calls carry a full-screen intent.
        // Counting ringing calls lets us warn about a foreign number before it's even answered.
        // Missed-call notifications are neither, so they're ignored.
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        return ongoing || n.fullScreenIntent != null
    }

    companion object {
        private const val LONG_CALL_MS = 10 * 60_000L
        private val VOIP_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.skype.raider", "com.google.android.apps.tachyon", "com.facebook.orca", "com.instagram.android",
        )
        private val SMS_APPS = setOf(
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.android.messaging", "com.miui.mms", "com.oneplus.mms", "com.truecaller",
        )

        /** Notification key → package, for calls in progress right now. */
        val activeCalls = ConcurrentHashMap<String, String>()
        @Volatile var lastCallEndedAt = 0L
    }
}
