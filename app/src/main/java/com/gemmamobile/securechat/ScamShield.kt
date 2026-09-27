package com.gemmamobile.securechat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Scam Shield: real-time warnings against OTP fraud and "digital arrest" scams.
 *
 * Privacy: no SMS permission and no access to conversations (chats). Call notifications and plain
 * SMS-app alerts are inspected in memory only, at the moment they arrive.
 * Nothing is stored, logged or sent anywhere; the only thing persisted is which protections are on.
 */
object ScamShield {

    // ------------------------------------------------------------------ Settings

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shield", Context.MODE_PRIVATE)

    fun otpGuardOn(ctx: Context) = prefs(ctx).getBoolean("otp", true)
    fun setOtpGuard(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("otp", on).apply()
    fun callGuardOn(ctx: Context) = prefs(ctx).getBoolean("call", true)
    fun setCallGuard(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("call", on).apply()

    // ------------------------------------------------------------------ Call state

    /**
     * True if the phone is on any call (mobile or WhatsApp/VoIP), or one ended in the last 2 minutes —
     * scammers often ask for the OTP right after "reconnecting".
     */
    fun inOrJustAfterCall(ctx: Context): Boolean {
        if (CallGuardService.activeCalls.isNotEmpty()) return true
        if (System.currentTimeMillis() - CallGuardService.lastCallEndedAt < 2 * 60_000) return true
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return true
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            return runCatching { ctx.getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)
        }
        return false
    }

    // ------------------------------------------------------------------ Detection

    private val otpKeywords = Regex(
        "(?i)(otp|one[ -]?time|verification code|security code|passcode|password|\\bpin\\b|\\bcode\\b|" +
            "ओटीपी|पासवर्ड|कोड|सत्यापन)"
    )
    private val otpDigits = Regex("(?<![\\d₹.,])\\d{4,8}(?![\\d,])")

    fun looksLikeOtp(body: String) = otpKeywords.containsMatchIn(body) && otpDigits.containsMatchIn(body)

    /** Caller shown as a raw number (not a saved contact name). */
    fun isRawNumber(title: String) = Regex("^[+\\d][\\d\\s\\-()]{6,}$").matches(title.trim())

    /** International number that isn't Indian (+91). */
    fun isForeignNumber(title: String): Boolean {
        val t = title.trim().replace(Regex("[\\s\\-()]"), "")
        return t.startsWith("+") && !t.startsWith("+91") && isRawNumber(title)
    }

    // ------------------------------------------------------------------ Alerts

    enum class Alert(val strong: Boolean, val title: String, val body: String) {
        OTP_DURING_CALL(
            true,
            "STOP — don't share this OTP",
            "You received an OTP while on a call. Banks, police, CBI, courier and telecom companies NEVER ask for an " +
                "OTP on a call. If someone is asking for it, hang up now.\n\n" +
                "आप कॉल पर हैं — यह OTP किसी को न बताएं। बैंक या पुलिस कभी OTP नहीं मांगते। तुरंत कॉल काटें।",
        ),
        FOREIGN_CALL(
            true,
            "International call — be careful",
            "This call is from outside India. Scammers pretending to be police, CBI, customs, TRAI or courier " +
                "companies often call from abroad. Don't share OTPs, Aadhaar or bank details, and don't pay anything.\n\n" +
                "यह विदेशी नंबर है। पुलिस/CBI बनकर ठगी करने वाले ऐसे ही कॉल करते हैं। कोई जानकारी या पैसा न दें।",
        ),
        UNKNOWN_VIDEO_CALL(
            false,
            "Video call from an unknown number",
            "There is NO \"digital arrest\" in Indian law. Police, CBI, ED or a court never question you, arrest you " +
                "or ask for money over a video call. If they threaten you, hang up and call 1930.\n\n" +
                "\"डिजिटल अरेस्ट\" जैसा कुछ नहीं होता। पुलिस वीडियो कॉल पर गिरफ्तार नहीं करती। डराएं तो कॉल काटें, 1930 पर कॉल करें।",
        ),
        LONG_UNKNOWN_CALL(
            true,
            "Long call with an unknown number",
            "You've been on this call for over 10 minutes. If the caller says they're police/CBI/customs, says you're " +
                "under \"digital arrest\", tells you not to tell family, or asks you to transfer money \"for verification\" " +
                "— it is a SCAM. Hang up now and call 1930.\n\n" +
                "अगर कॉल करने वाला खुद को पुलिस/CBI बताए, गिरफ्तारी की धमकी दे या पैसे मांगे — यह धोखा है। कॉल काटें, 1930 पर कॉल करें।",
        ),
        TEST(
            true,
            "Scam Shield test",
            "This is how a warning looks. When a real risk is detected you'll see this screen even if the phone is locked.",
        ),
    }

    private const val CHANNEL_STRONG = "scam_alert_strong"
    private const val CHANNEL_SOFT = "scam_alert_soft"

    /**
     * Quiet notification after a call with an unknown number ends: one tap to Scam Check it,
     * or (phone calls) to analyse the phone's call recording.
     */
    fun afterCallCheckup(ctx: Context, caller: String, phoneCall: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_AFTER_CALL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AFTER_CALL, "After-call check-up", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        fun open(action: String, code: Int) = PendingIntent.getActivity(
            ctx, code,
            Intent(ctx, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val scamCheck = open(MainActivity.ACTION_SCAM_CHECK, 20)
        val n = NotificationCompat.Builder(ctx, CHANNEL_AFTER_CALL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Call with unknown number ended")
            .setContentText("Anything suspicious? Check it with Gemma before you pay or share anything.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Call with ${caller.ifBlank { "an unknown number" }} ended. Were you asked for money, an OTP, " +
                        "to stay on a video call, or told you're under \"digital arrest\"? Check it before doing anything."
                )
            )
            .setContentIntent(scamCheck)
            .addAction(0, "Scam Check", scamCheck)
            .apply { if (phoneCall) addAction(0, "Check recording", open(MainActivity.ACTION_CHECK_RECORDING, 21)) }
            .setAutoCancel(true)
            .setTimeoutAfter(6 * 60 * 60_000L)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { nm.notify(2000, n) }
    }

    private const val CHANNEL_AFTER_CALL = "after_call"

    private val recentAlerts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun show(ctx: Context, alert: Alert, detail: String? = null) {
        // A ringing call that gets answered can re-post as a new notification: don't warn twice.
        // Long-call warnings can come from two sources for the same call, so dedupe those by type.
        val key = if (alert == Alert.LONG_UNKNOWN_CALL || alert == Alert.FOREIGN_CALL) alert.name else alert.name + "|" + detail
        val now = System.currentTimeMillis()
        val last = recentAlerts.put(key, now)
        if (alert != Alert.TEST && last != null && now - last < 3 * 60_000) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_STRONG) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STRONG, "Scam warnings (urgent)", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 600, 200, 600, 200, 600)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SOFT, "Scam tips", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = Intent(ctx, ScamAlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(ScamAlertActivity.EXTRA_ALERT, alert.name)
            .putExtra(ScamAlertActivity.EXTRA_DETAIL, detail)
        val pi = PendingIntent.getActivity(
            ctx, alert.ordinal, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, if (alert.strong) CHANNEL_STRONG else CHANNEL_SOFT)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ " + alert.title)
            .setContentText(alert.body.substringBefore("\n"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setColor(0xFFD32F2F.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { if (alert.strong) setFullScreenIntent(pi, true) }
            .build()
        runCatching { nm.notify(1000 + alert.ordinal, n) }
    }
}
