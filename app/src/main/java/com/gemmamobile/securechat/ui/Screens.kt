package com.gemmamobile.securechat.ui

import android.graphics.Color as AndroidColor
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VoiceOverOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gemmamobile.securechat.BuildConfig
import com.gemmamobile.securechat.ChatMessage
import com.gemmamobile.securechat.ChatViewModel
import com.gemmamobile.securechat.LiveTranscriber
import com.gemmamobile.securechat.ModelSpec
import com.gemmamobile.securechat.ModelState
import com.gemmamobile.securechat.Security
import com.gemmamobile.securechat.VoiceController

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF9FA8DA), primaryContainer = Color(0xFF283593))
    } else {
        lightColorScheme(primary = Color(0xFF283593), primaryContainer = Color(0xFFE8EAF6))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun AppRoot(
    vm: ChatViewModel,
    canLock: Boolean,
    lockError: String?,
    onUnlock: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    voice: VoiceController,
    onMic: () -> Unit,
    liveTranscriber: LiveTranscriber,
    onStartMeeting: () -> Unit,
    onStopMeeting: () -> Unit,
    onResumeMeeting: () -> Unit,
    onPickRecording: () -> Unit,
    shieldStatus: ShieldStatus,
    shieldActions: ShieldActions,
    onPermissionsDone: () -> Unit,
    onPickModel: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !canLock -> NeedsScreenLock(onOpenSecuritySettings)
            !vm.unlocked -> LockedScreen(lockError, onUnlock)
            else -> when (val s = vm.modelState) {
                is ModelState.Ready -> {
                    val session = vm.session
                    if (vm.showPermissions) {
                        PermissionsScreen(shieldStatus, shieldActions, onPermissionsDone)
                    } else if (vm.showShield) {
                        ShieldScreen(shieldStatus, shieldActions)
                    } else if (session != null) {
                        NotesScreen(vm, session, liveTranscriber, onStopMeeting, onResumeMeeting)
                    } else {
                        ChatScreen(vm, s.backend, voice, onMic, onStartMeeting, onPickRecording, shieldActions.onScamCheck)
                    }
                }
                else -> SetupScreen(vm, s, onPickModel)
            }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun LockedScreen(error: String?, onUnlock: () -> Unit) = CenteredColumn {
    Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text("Gemma Secure Chat is locked", style = MaterialTheme.typography.titleMedium)
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(24.dp))
    Button(onClick = onUnlock) { Text("Unlock") }
}

@Composable
private fun NeedsScreenLock(onOpenSettings: () -> Unit) = CenteredColumn {
    Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(16.dp))
    Text(
        "For your privacy this app requires a device screen lock (PIN, pattern, password or biometrics).",
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    Button(onClick = onOpenSettings) { Text("Open security settings") }
}

@Composable
private fun SetupScreen(vm: ChatViewModel, state: ModelState, onPickModel: () -> Unit) = CenteredColumn {
    Text("On-device AI model", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(12.dp))
    when (state) {
        is ModelState.Installing -> {
            Text(state.label)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("%.1f%%".format(state.progress * 100))
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = vm::cancelInstall) { Text("Cancel") }
        }
        ModelState.Loading -> {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(
                "Loading ${vm.selectedModel.title} into memory…\nThis happens once; after that the app opens instantly.",
                textAlign = TextAlign.Center,
            )
        }
        else -> {
            if (state is ModelState.Failed) {
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "The model runs entirely inside this app — your messages never leave the phone. " +
                    "Each file is checked against a pinned SHA-256 before use.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            ModelSpec.entries.forEach { spec ->
                val chosen = vm.selectedModel == spec
                val installed = spec in vm.installedModels
                Card(
                    onClick = { vm.chooseModel(spec) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = if (chosen) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen, onClick = { vm.chooseModel(spec) })
                        Column(Modifier.weight(1f)) {
                            Text(spec.title, fontWeight = FontWeight.SemiBold)
                            Text(spec.blurb, style = MaterialTheme.typography.bodySmall)
                            if (installed) {
                                Text("On this phone — tap to use", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (BuildConfig.ALLOW_DOWNLOAD && vm.selectedModel !in vm.installedModels) {
                Button(onClick = vm::downloadModel, modifier = Modifier.fillMaxWidth()) {
                    Text("Download ${vm.selectedModel.sizeGb} (one time, Wi-Fi)")
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onPickModel, modifier = Modifier.fillMaxWidth()) {
                Text("Import a model file from phone storage")
            }
            val other = vm.installedModels.filter { it != vm.selectedModel }
            if (other.isNotEmpty() && vm.selectedModel in vm.installedModels) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: delete ${other.joinToString { it.title }} from the chat menu to free up space.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
            if (state is ModelState.Failed) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::retry) { Text("Retry loading") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    vm: ChatViewModel,
    backend: String,
    voice: VoiceController,
    onMic: () -> Unit,
    onStartMeeting: () -> Unit,
    onPickRecording: () -> Unit,
    onScamCheck: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val lastText = vm.messages.lastOrNull()?.text

    LaunchedEffect(vm.messages.size, lastText) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (vm.scamCheckMode) "Scam Check" else "Gemma Secure Chat")
                        Text(
                            if (vm.scamCheckMode) "Offline · tap + to leave" else "Offline · $backend",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.showShield = true }) {
                        Icon(Icons.Default.Shield, "Scam Shield", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconToggleButton(checked = voice.voiceMode, onCheckedChange = { voice.toggleVoiceMode() }) {
                        if (voice.voiceMode) {
                            Icon(Icons.Default.RecordVoiceOver, "Voice mode on", tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.VoiceOverOff, "Voice mode off")
                        }
                    }
                    IconButton(onClick = { voice.halt(); vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Live meeting notes") },
                            leadingIcon = { Icon(Icons.Default.Groups, null) },
                            onClick = { menuOpen = false; onStartMeeting() },
                        )
                        DropdownMenuItem(
                            text = { Text("Summarize a call recording") },
                            leadingIcon = { Icon(Icons.Default.PhoneInTalk, null) },
                            onClick = { menuOpen = false; onPickRecording() },
                        )
                        DropdownMenuItem(
                            text = { Text("Permissions") },
                            leadingIcon = { Icon(Icons.Default.Lock, null) },
                            onClick = { menuOpen = false; vm.showPermissions = true },
                        )
                        DropdownMenuItem(
                            text = { Text("Switch model (fast / smart)") },
                            leadingIcon = { Icon(Icons.Default.Speed, null) },
                            onClick = { menuOpen = false; vm.openModelPicker() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete model from phone") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            InputBar(
                generating = vm.isGenerating,
                voice = voice,
                onMic = onMic,
                onSend = vm::send,
                onStop = { voice.stopSpeaking(); vm.stopGeneration() },
            )
        },
    ) { padding ->
        if (vm.messages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onStartMeeting) {
                        Icon(Icons.Default.Groups, null); Spacer(Modifier.size(6.dp)); Text("Live meeting")
                    }
                    OutlinedButton(onClick = onPickRecording) {
                        Icon(Icons.Default.PhoneInTalk, null); Spacer(Modifier.size(6.dp)); Text("Call recording")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = onScamCheck) {
                    Icon(Icons.Default.Shield, null); Spacer(Modifier.size(6.dp)); Text("Scam Check — is this call a fraud?")
                }
                Text(
                    "Type, or tap the mic to talk — spoken questions get spoken answers.\n" +
                        "Tap any reply to hear it again.\n" +
                        "Voice mode (top bar) reads every answer and keeps listening, hands-free.\n\n" +
                        "Chats are kept in memory only and are erased when you close the app.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.messages, key = { it.id }) { Bubble(it, onSpeak = voice::speakOnce) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete model?") },
            text = { Text("${vm.selectedModel.title} (${vm.selectedModel.sizeGb}) will be removed from the phone. You'll need to download or import it again to use it.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteModel() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChatMessage, onSpeak: (String) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val bg = when {
        m.isError -> colors.errorContainer
        m.fromUser -> colors.primary
        else -> colors.surfaceVariant
    }
    val fg = when {
        m.isError -> colors.onErrorContainer
        m.fromUser -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(bg, RoundedCornerShape(16.dp))
                .combinedClickable(
                    // Tap a reply to hear it; long-press any message to copy it.
                    onClick = { if (!m.fromUser && !m.streaming && !m.isError) onSpeak(m.text) },
                    onLongClick = { Security.copySensitive(context, m.text) },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (m.streaming && m.text.isEmpty()) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fg)
            } else {
                Text(m.text, color = fg)
            }
        }
    }
}

/**
 * Uses a platform EditText so we can set IME_FLAG_NO_PERSONALIZED_LEARNING, which asks the
 * keyboard not to learn from or remember what is typed here (Compose TextField can't set it).
 */
@Composable
private fun InputBar(
    generating: Boolean,
    voice: VoiceController,
    onMic: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var editText by remember { mutableStateOf<EditText?>(null) }
    val submit = {
        editText?.let { et ->
            val text = et.text.toString()
            if (text.isNotBlank()) {
                onSend(text)
                et.text.clear()
            }
        }
    }
    val listening = voice.phase == VoiceController.Phase.Listening
    // Mirror the live transcript into the text box while listening.
    LaunchedEffect(voice.partialText, listening) {
        if (listening) editText?.let { et ->
            et.setText(voice.partialText)
            et.setSelection(et.text.length)
        } else if (voice.partialText.isEmpty()) {
            // Final transcript was sent by the voice controller; clear the box.
            editText?.text?.clear()
        }
    }
    Surface(tonalElevation = 3.dp) {
      Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
        val statusText = when {
            listening -> "Listening… (tap mic to finish)"
            voice.phase == VoiceController.Phase.Speaking -> "Speaking… (tap stop to interrupt)"
            else -> voice.status
        }
        if (statusText != null) {
            Text(
                statusText,
                style = MaterialTheme.typography.labelMedium,
                color = if (voice.status != null && !listening) colors.error else colors.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 160.dp),
                factory = { ctx ->
                    EditText(ctx).apply {
                        hint = "Type a message"
                        inputType = InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        maxLines = 6
                        setTextColor(colors.onSurface.toArgb())
                        setHintTextColor(colors.onSurfaceVariant.toArgb())
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        setPadding(24, 16, 24, 16)
                        filters = arrayOf(android.text.InputFilter.LengthFilter(ChatViewModel.MAX_PROMPT_CHARS))
                        editText = this
                    }
                },
            )
            Spacer(Modifier.size(4.dp))
            if (listening) {
                FilledIconButton(
                    onClick = onMic,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.error),
                ) { Icon(Icons.Default.Mic, "Stop listening") }
            } else {
                IconButton(onClick = onMic, enabled = !generating) { Icon(Icons.Default.Mic, "Speak") }
            }
            Spacer(Modifier.size(4.dp))
            if (generating || voice.phase == VoiceController.Phase.Speaking) {
                FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = { submit() }, enabled = !listening) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
      }
    }
}
