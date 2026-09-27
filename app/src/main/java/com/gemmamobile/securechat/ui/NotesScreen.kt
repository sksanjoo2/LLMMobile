package com.gemmamobile.securechat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gemmamobile.securechat.ChatViewModel
import com.gemmamobile.securechat.LiveTranscriber
import com.gemmamobile.securechat.NotesSession
import com.gemmamobile.securechat.Security
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    vm: ChatViewModel,
    s: NotesSession,
    liveTranscriber: LiveTranscriber,
    onStopMeeting: () -> Unit,
    onResumeMeeting: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val isMeeting = s.kind == NotesSession.Kind.Meeting
    val listening = s.micActive || liveTranscriber.running
    var confirmClose by remember { mutableStateOf(false) }

    // Ticking clock for the meeting timer.
    var now by remember { mutableIntStateOf(s.elapsedSec()) }
    LaunchedEffect(listening) {
        while (listening) { now = s.elapsedSec(); delay(1000) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(s.segments.size) {
        if (s.segments.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (isMeeting) "Live meeting notes" else "Call recording notes")
                        Text(
                            "On-device · nothing leaves this phone",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (s.finished) vm.closeSession() else confirmClose = true }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        isMeeting && listening -> Button(
                            onClick = onStopMeeting,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Stop, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop meeting")
                        }
                        isMeeting && !s.inputDone -> {
                            OutlinedButton(onClick = onResumeMeeting, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Resume")
                            }
                            Button(onClick = onStopMeeting, modifier = Modifier.weight(1f)) { Text("Finish") }
                        }
                        !isMeeting && !s.inputDone -> OutlinedButton(
                            onClick = vm::stopSession,
                            modifier = Modifier.weight(1f),
                        ) { Text("Stop here & summarize") }
                        s.finished -> {
                            OutlinedButton(
                                onClick = {
                                    Security.copySensitive(context, s.notes + "\n\n--- Transcript ---\n" + s.transcriptText())
                                },
                            ) { Icon(Icons.Default.ContentCopy, "Copy notes and transcript") }
                            OutlinedButton(onClick = vm::scamCheckSession, modifier = Modifier.weight(1f)) {
                                Text("Scam check")
                            }
                            Button(onClick = vm::askAboutSession, modifier = Modifier.weight(1f)) {
                                Text("Ask questions")
                            }
                        }
                        else -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Finishing notes…")
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                StatusRow(s, listening, now)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (s.updatingNotes) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("updating", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        val shown = s.notesDraft.ifBlank { s.notes }
                        Text(
                            shown.ifBlank {
                                if (isMeeting) "Notes appear after about a minute of conversation and keep updating."
                                else "Notes appear as the call is transcribed."
                            },
                            color = if (shown.isBlank()) colors.onSurfaceVariant else colors.onSurface,
                        )
                    }
                }
            }
            s.error?.let { err ->
                item { Text(err, color = colors.error, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Text(
                    "Transcript",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (s.segments.isEmpty() && liveTranscriber.partial.isBlank()) {
                item {
                    Text(
                        if (isMeeting) "Waiting for speech…" else "Transcript will appear here.",
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            items(s.segments) { seg ->
                Row {
                    Text(
                        NotesSession.fmt(seg.atSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        modifier = Modifier.width(44.dp).padding(top = 3.dp),
                    )
                    Text(seg.text)
                }
            }
            if (liveTranscriber.running && liveTranscriber.partial.isNotBlank()) {
                item { Text(liveTranscriber.partial, fontStyle = FontStyle.Italic, color = colors.onSurfaceVariant) }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Discard this session?") },
            text = { Text("Recording stops and the transcript and notes are erased.") },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; vm.closeSession() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun StatusRow(s: NotesSession, listening: Boolean, now: Int) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        if (listening) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(colors.error, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Recording ${NotesSession.fmt(now)}",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (s.micActive) {
                Text(
                    "Keeps running with the screen off or in other apps. Stop it from here or the notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        s.status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        s.progress?.let { p ->
            if (!s.finished) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant, RoundedCornerShape(1.dp)))
    }
}
