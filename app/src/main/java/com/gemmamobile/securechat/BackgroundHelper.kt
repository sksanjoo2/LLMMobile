package com.gemmamobile.securechat

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Helps the app survive in the background on phones whose makers add their own app killers,
 * and lets the user verify it actually works.
 */
object BackgroundHelper {

    /** Phone makers known to block background starts unless "Auto-start" is enabled. */
    val needsAutoStart: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf(
            "xiaomi", "redmi", "poco", "oppo", "realme", "vivo", "iqoo", "oneplus", "huawei", "honor", "tecno", "infinix", "itel",
        )

    val brand: String get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    /** Opens the maker's Auto-start / background-launch screen, or App info as a fallback. */
    fun openAutoStart(ctx: Context) {
        // OxygenOS 13+ keeps "Allow auto launch" / "Allow background activity" under
        // App info → Battery usage, so go straight to App info on OnePlus.
        if (Build.MANUFACTURER.equals("oneplus", ignoreCase = true)) {
            openAppInfo(ctx)
            return
        }
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        )
        for (c in candidates) {
            val i = Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        openAppInfo(ctx)
    }

    private fun openAppInfo(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Self-test: in about a minute, the app wakes itself up in the background and posts a notification.
     * If it never arrives with the app closed and screen off, the phone is killing the app.
     */
    fun scheduleSelfTest(ctx: Context) {
        val pi = PendingIntent.getBroadcast(
            ctx, 9, Intent(ctx, BackgroundTestReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000, pi,
        )
    }
}

class BackgroundTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel("bg_test") == null) {
            nm.createNotificationChannel(NotificationChannel("bg_test", "Background test", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(context, "bg_test")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("✅ Background works")
            .setContentText("Gemma Secure Chat ran in the background. Call protection can start on its own.")
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(99, n) }
    }
}
