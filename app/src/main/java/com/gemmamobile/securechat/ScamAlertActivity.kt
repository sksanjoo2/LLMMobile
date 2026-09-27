package com.gemmamobile.securechat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-screen scam warning, shown over the lock screen and over the call.
 * Shows no private data (never the OTP), so it doesn't need the app lock.
 */
class ScamAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val alert = runCatching { ScamShield.Alert.valueOf(intent.getStringExtra(EXTRA_ALERT).orEmpty()) }
            .getOrDefault(ScamShield.Alert.TEST)
        val detail = intent.getStringExtra(EXTRA_DETAIL)

        setContent {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFFB71C1C))
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    alert.title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Text(alert.body, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center)
                if (detail != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(detail, color = Color(0xFFFFCDD2), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C)),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Call 1930 — Cyber Fraud Helpline", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        startActivity(
                            Intent(this@ScamAlertActivity, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_SCAM_CHECK)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Not sure? Ask Gemma (Scam Check)", color = Color.White) }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) {
                    Text("I understand", color = Color.White)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Report fraud: cybercrime.gov.in · Report fraud calls: Sanchar Saathi → Chakshu",
                    color = Color(0xFFFFCDD2),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    companion object {
        const val EXTRA_ALERT = "alert"
        const val EXTRA_DETAIL = "detail"
    }
}
