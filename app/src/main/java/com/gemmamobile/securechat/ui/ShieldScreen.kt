package com.gemmamobile.securechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What the Scam Shield can currently do, recomputed each time the app resumes. */
data class ShieldStatus(
    val phoneStateAllowed: Boolean = false,
    val listenerEnabled: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val fullScreenAllowed: Boolean = false,
    val otpGuardOn: Boolean = true,
    val callGuardOn: Boolean = true,
    val micAllowed: Boolean = false,
    /** Holds the "Caller ID & spam" role, so Android starts the app automatically on unknown calls. */
    val callerRoleHeld: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    /** Shown when Android blocked a permission popup and we sent the user to App info instead. */
    val hint: String? = null,
)

class ShieldActions(
    val onBack: () -> Unit,
    val onGrantPhone: () -> Unit,
    val onOpenNotificationAccess: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val onGrantNotifications: () -> Unit,
    val onOpenFullScreenSettings: () -> Unit,
    val onToggleOtp: (Boolean) -> Unit,
    val onToggleCall: (Boolean) -> Unit,
    val onTestAlert: () -> Unit,
    val onScamCheck: () -> Unit,
    val onGrantMic: () -> Unit,
    val onRequestCallerRole: () -> Unit,
    val onOpenAutoStart: () -> Unit,
    val onBackgroundTest: () -> Unit,
    val onBatteryUnrestricted: () -> Unit,
    val onAllowAll: () -> Unit,
    val onOpenPermissions: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onOpenWhatsApp: () -> Unit,
    val onOpenPhoneApp: () -> Unit,
    val onCall1930: () -> Unit,
    val onPickRecording: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShieldScreen(status: ShieldStatus, a: ShieldActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scam Shield") },
                navigationIcon = { IconButton(onClick = a.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Real-time protection against OTP fraud and \"digital arrest\" scams. Everything is checked on this " +
                    "phone at the moment it happens — messages and calls are never stored or sent anywhere.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(onClick = a.onScamCheck, modifier = Modifier.fillMaxWidth()) {
                Text("Scam Check — ask Gemma about a call or message")
            }
            status.hint?.let { HintCard(it, a.onOpenAppInfo) }

            GuardCard(
                title = "OTP-during-call alarm",
                desc = "No SMS permission needed. If your SMS app shows an OTP while you're on any call (phone or " +
                    "WhatsApp), a full-screen warning " +
                    "tells you not to share it.",
                on = status.otpGuardOn,
                onToggle = a.onToggleOtp,
                steps = listOf(
                    Step("Turn on notification access (see below)", status.listenerEnabled, a.onOpenNotificationAccess),
                    Step("Allow phone-call detection (optional)", status.phoneStateAllowed, a.onGrantPhone),
                ),
            )

            GuardCard(
                title = "Risky call alert (digital arrest)",
                desc = "Warns on calls from foreign numbers, video calls from unknown numbers, and any call with an " +
                    "unknown number that lasts over 10 minutes. Works for phone, WhatsApp, Telegram and Signal calls. " +
                    "Also lets the OTP alarm detect WhatsApp calls.",
                on = status.callGuardOn,
                onToggle = a.onToggleCall,
                steps = listOf(
                    Step("Phone calls: set as Caller ID & spam app (starts automatically on unknown calls)", status.callerRoleHeld, a.onRequestCallerRole),
                    Step("WhatsApp & other app calls: turn on notification access", status.listenerEnabled, a.onOpenNotificationAccess),
                ),
                note = if (!status.listenerEnabled) {
                    "Greyed out / \"Restricted setting\"? Open App info → ⋮ (top right) → Allow restricted settings, then try again."
                } else null,
                noteAction = if (!status.listenerEnabled) "Open App info" to a.onOpenAppInfo else null,
            )

            GuardCard(
                title = "Warning display",
                desc = "Lets warnings pop up full-screen, even on the lock screen and over the call.",
                on = null,
                onToggle = {},
                steps = listOf(
                    Step("Allow notifications", status.notificationsAllowed, a.onGrantNotifications),
                    Step("Allow full-screen alerts", status.fullScreenAllowed, a.onOpenFullScreenSettings),
                ),
            )

            SafetyChecklist(a)

            OutlinedButton(onClick = a.onTestAlert, modifier = Modifier.fillMaxWidth()) { Text("Show a test warning") }
            TextButton(onClick = a.onOpenPermissions, modifier = Modifier.fillMaxWidth()) { Text("See all app permissions") }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("Remember", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• There is no \"digital arrest\" — police never arrest or question anyone on a video call.\n" +
                            "• Never share OTP, UPI PIN or card details on a call.\n" +
                            "• You never need to enter your UPI PIN to receive money.\n" +
                            "• Lost money? Call 1930 immediately and report at cybercrime.gov.in.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyChecklist(a: ShieldActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("Safety checklist", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            ChecklistItem(
                "WhatsApp: Settings → Privacy → Calls → turn on \"Silence unknown callers\". " +
                    "Most digital-arrest scams start as WhatsApp video calls from unknown numbers.",
                "Open WhatsApp", a.onOpenWhatsApp,
            )
            ChecklistItem(
                "Turn on call recording in your Phone app (⋮ → Settings → Call recording). After a suspicious " +
                    "call, share the recording here and tap Scam check.",
                "Open Phone app", a.onOpenPhoneApp,
            )
            ChecklistItem("Already have a call recording? Check it for fraud now.", "Check a recording", a.onPickRecording)
            ChecklistItem(
                "Report a fraud call or SMS number (Chakshu, Sanchar Saathi).",
                "Report number", { a.onOpenUrl("https://sancharsaathi.gov.in/sfc/") },
            )
            ChecklistItem(
                "Lost money or shared details? Report it immediately — the first hours matter.",
                "cybercrime.gov.in", { a.onOpenUrl("https://cybercrime.gov.in") },
            )
            Button(onClick = a.onCall1930, modifier = Modifier.fillMaxWidth()) { Text("Call 1930 — Cyber Fraud Helpline") }
        }
    }
}

@Composable
private fun ChecklistItem(text: String, action: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("• $text", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
fun HintCard(text: String, onOpenAppInfo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = colors.errorContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(text, color = colors.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onOpenAppInfo) { Text("Open App info") }
        }
    }
}

private data class Step(val label: String, val done: Boolean, val action: () -> Unit)

@Composable
private fun GuardCard(
    title: String,
    desc: String,
    on: Boolean?,
    onToggle: (Boolean) -> Unit,
    steps: List<Step>,
    note: String? = null,
    noteAction: Pair<String, () -> Unit>? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (on != null) Switch(checked = on, onCheckedChange = onToggle)
            }
            Text(desc, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            steps.forEach { step ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        if (step.done) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                        null,
                        tint = if (step.done) colors.primary else colors.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(step.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (!step.done) TextButton(onClick = step.action) { Text("Fix") }
                }
            }
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                noteAction?.let { (label, act) -> TextButton(onClick = act) { Text(label) } }
            }
        }
    }
}
