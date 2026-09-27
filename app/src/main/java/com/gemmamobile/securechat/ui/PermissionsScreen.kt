package com.gemmamobile.securechat.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown once after install (and from ⋮ → Permissions): every permission the app uses,
 * why, and a one-tap way to allow it. Nothing is required — each feature just stays off without it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(status: ShieldStatus, a: ShieldActions, onDone: () -> Unit) {
    val items = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Perm(Icons.Default.Notifications, "Notifications", "Scam warnings, and the recording / progress notice.", status.notificationsAllowed, a.onGrantNotifications))
        }
        add(Perm(Icons.Default.Mic, "Microphone", "Voice chat and live meeting notes. Only used while you've turned it on.", status.micAllowed, a.onGrantMic))
        add(Perm(Icons.Default.Security, "Caller ID & spam protection", "Android starts the app automatically when a call from an unknown number begins: warns about foreign numbers and long \"digital arrest\" calls. Never blocks calls. Normal popup — no restricted setting.", status.callerRoleHeld, a.onRequestCallerRole))
        add(Perm(Icons.Default.PhoneInTalk, "Notification access", "Risky-call and digital-arrest alerts, and the OTP-during-call alarm. Only call notifications and plain SMS-app alerts are looked at, never stored. Conversations (chats) are NOT accessed — leave that type off.", status.listenerEnabled, a.onOpenNotificationAccess))
        add(Perm(Icons.Default.Call, "Phone call detection", "Optional. Helps the OTP alarm know you're on a normal phone call.", status.phoneStateAllowed, a.onGrantPhone))
        if (Build.VERSION.SDK_INT >= 34) {
            add(Perm(Icons.Default.Fullscreen, "Full-screen alerts", "Lets a scam warning cover the screen during a call, even when locked.", status.fullScreenAllowed, a.onOpenFullScreenSettings))
        }
        add(Perm(Icons.Default.BatteryFull, "Run in background", "Keeps meeting recording and call alerts working when the screen is off.", status.batteryUnrestricted, a.onBatteryUnrestricted))
        if (com.gemmamobile.securechat.BackgroundHelper.needsAutoStart) {
            // Can't be detected, so it always shows an Open button.
            add(Perm(Icons.Default.PlayArrow, "Auto-start (${com.gemmamobile.securechat.BackgroundHelper.brand})", if (android.os.Build.MANUFACTURER.equals("oneplus", true)) "OnePlus blocks background starts. In App info → Battery usage, turn ON \"Allow auto launch\" and \"Allow background activity\"." else "Your phone brand blocks apps from starting in the background. Turn ON Auto-start for Gemma Secure Chat, or call protection can't start by itself.", false, a.onOpenAutoStart, actionLabel = "Open"))
        }
    }
    val remaining = items.count { !it.granted }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Permissions") }) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                    if (remaining > 0) {
                        Button(onClick = a.onAllowAll, modifier = Modifier.fillMaxWidth()) { Text("Allow all") }
                    }
                    androidx.compose.material3.TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(if (remaining == 0) "Done" else "Continue — I'll do the rest later")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Allow these so every feature works. Everything stays on this phone — nothing is uploaded. " +
                    "You can change them any time from ⋮ → Permissions.",
                style = MaterialTheme.typography.bodyMedium,
            )
            status.hint?.let { HintCard(it, a.onOpenAppInfo) }
            items.forEach { PermRow(it) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Check background is working", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Tap Test, then close the app (Home button — don't swipe it away) and lock the screen. " +
                            "In about 1 minute you should get \"✅ Background works\". If it doesn't come, " +
                            "turn on the items above (especially Auto-start and Run in background).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = a.onBackgroundTest) { Text("Test") }
                }
            }
            Text(
                "Installed from an APK? If Android says \"Restricted setting\" or a switch is greyed out: " +
                    "App info → ⋮ (top-right) → Allow restricted settings, then come back.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(onClick = a.onOpenAppInfo) { Text("Open App info") }
        }
    }
}

private data class Perm(
    val icon: ImageVector,
    val title: String,
    val why: String,
    val granted: Boolean,
    val action: () -> Unit,
    val actionLabel: String = "Allow",
)

@Composable
private fun PermRow(p: Perm) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(p.icon, null, tint = colors.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontWeight = FontWeight.SemiBold)
                Text(p.why, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (p.granted) {
                Icon(Icons.Default.CheckCircle, "Allowed", tint = colors.primary)
            } else {
                FilledTonalButton(onClick = p.action) { Text(p.actionLabel) }
            }
        }
    }
}
