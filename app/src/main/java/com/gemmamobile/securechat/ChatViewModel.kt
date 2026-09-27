package com.gemmamobile.securechat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val streaming: Boolean = false,
    val isError: Boolean = false,
)

sealed interface ModelState {
    /** No verified model on the device yet. */
    data object Missing : ModelState
    /** Downloading / importing / verifying. */
    data class Installing(val label: String, val progress: Float) : ModelState
    /** Loading weights into memory. */
    data object Loading : ModelState
    data class Ready(val backend: String) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * Chat history is held only in memory: it is never written to disk, never backed up,
 * and disappears when the app process ends or the user taps "New chat".
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val models = ModelManager(app)
    // LiteRT-LM objects are not thread-safe; confine all engine work to one thread, and one
    // user at a time (chat, live notes, transcription — each resets the conversation).
    // The engine lives in EngineHolder so it stays loaded when this screen is closed and reopened.
    private val engineDispatcher = EngineHolder.dispatcher
    private val engineMutex = EngineHolder.mutex
    private var engine: LlmEngine?
        get() = EngineHolder.engine
        set(v) { EngineHolder.engine = v }
    private var generation: Job? = null
    private var installJob: Job? = null
    private var nextId = 0L

    val messages = mutableStateListOf<ChatMessage>()
    /** Model chosen in the picker (fast E2B / smart E4B). */
    var selectedModel by mutableStateOf(models.selected)
        private set
    var installedModels by mutableStateOf(models.installed())
        private set
    var modelState by mutableStateOf<ModelState>(ModelState.Missing)
        private set
    var isGenerating by mutableStateOf(false)
        private set

    /** True while the chat is in Scam Check mode (Gemma acts as a fraud-risk analyst). */
    var scamCheckMode by mutableStateOf(false)
        private set
    /** Scam Shield settings screen visible. */
    var showShield by mutableStateOf(false)
    /** Permissions screen visible (once after the model is ready, or from ⋮ → Permissions). */
    var showPermissions by mutableStateOf(false)

    /** Active live meeting / call recording, or null for normal chat. */
    var session by mutableStateOf<NotesSession?>(null)
        private set
    private var sessionJob: Job? = null
    private var micRecorder: MicRecorder? = null
    private var micClips: Channel<Triple<File, Int, Boolean>>? = null

    /** App-lock state lives here so it survives configuration changes. */
    var unlocked by mutableStateOf(false)
    var skipNextLock = false

    init {
        if (models.isReady(selectedModel)) loadEngine() else modelState = ModelState.Missing
        // "Stop" tapped in the background notification.
        viewModelScope.launch { SessionService.stopEvents.collect { stopSession() } }
    }

    fun importModel(uri: Uri) = install("Importing & verifying model") { onProgress ->
        val spec = models.importFrom(getApplication(), uri, onProgress)
        models.selected = spec
        selectedModel = spec
    }

    fun downloadModel() {
        val spec = selectedModel
        install("Downloading ${spec.title}") { onProgress -> models.download(spec, onProgress) }
    }

    /** Picker selection. Loads it right away if it's already on the phone. */
    fun chooseModel(spec: ModelSpec) {
        selectedModel = spec
        models.selected = spec
        if (models.isReady(spec)) loadEngine()
    }

    /** Opens the model picker (Setup screen) from the chat menu. */
    fun openModelPicker() {
        closeSession(resetChat = false)
        stopGeneration()
        installedModels = models.installed()
        modelState = ModelState.Missing
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    private fun install(label: String, block: suspend ((Float) -> Unit) -> Unit) {
        if (installJob?.isActive == true) return
        installJob = viewModelScope.launch {
            modelState = ModelState.Installing(label, 0f)
            try {
                withContext(Dispatchers.IO) {
                    block { p -> modelState = ModelState.Installing(label, p) }
                }
                installedModels = models.installed()
                loadEngine()
            } catch (e: CancellationException) {
                modelState = ModelState.Missing
                throw e
            } catch (e: Throwable) {
                modelState = ModelState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun loadEngine() {
        val path = models.file(selectedModel).absolutePath
        val loaded = engine
        if (loaded != null && loaded.modelPath == path) {
            // Already in memory (screen was closed and reopened): instant start with a fresh chat.
            modelState = ModelState.Ready(loaded.backendName)
            viewModelScope.launch { engineMutex.withLock { withContext(engineDispatcher) { loaded.resetConversation() } } }
            return
        }
        modelState = ModelState.Loading
        viewModelScope.launch {
            try {
                val e = engineMutex.withLock {
                    withContext(engineDispatcher) {
                        // Free the previous model's memory before loading another one.
                        engine?.close()
                        engine = null
                        LlmEngine(getApplication(), path).also { it.initialize() }
                    }
                }
                engine = e
                messages.clear()
                modelState = ModelState.Ready(e.backendName)
            } catch (t: Throwable) {
                modelState = ModelState.Failed(t.message ?: "Failed to load model")
            }
        }
    }

    fun retry() {
        if (models.isReady(selectedModel)) loadEngine() else modelState = ModelState.Missing
    }

    fun deleteModel() {
        viewModelScope.launch {
            stopGeneration()
            engineMutex.withLock { withContext(engineDispatcher) { engine?.close(); engine = null } }
            withContext(Dispatchers.IO) { models.delete(selectedModel) }
            installedModels = models.installed()
            messages.clear()
            modelState = ModelState.Missing
        }
    }

    fun send(raw: String) {
        val prompt = raw.trim().take(MAX_PROMPT_CHARS)
        val e = engine ?: return
        if (prompt.isEmpty() || isGenerating) return

        messages += ChatMessage(nextId++, fromUser = true, text = prompt)
        val replyId = nextId++
        messages += ChatMessage(replyId, fromUser = false, text = "", streaming = true)
        isGenerating = true

        generation = viewModelScope.launch {
            val sb = StringBuilder()
            try {
                engineMutex.withLock {
                    withContext(engineDispatcher) {
                        e.send(prompt).collect { chunk ->
                            sb.append(chunk)
                            val text = sb.toString()
                            withContext(Dispatchers.Main) { update(replyId) { it.copy(text = text) } }
                        }
                    }
                }
                update(replyId) { it.copy(streaming = false, text = sb.toString().trim()) }
            } catch (c: CancellationException) {
                update(replyId) { it.copy(streaming = false, text = sb.toString().trim() + " …") }
            } catch (t: Throwable) {
                update(replyId) {
                    it.copy(
                        streaming = false,
                        isError = true,
                        text = "Error: ${t.message ?: "generation failed"}.\n" +
                            "If the chat is very long, tap \"New chat\" to free the context.",
                    )
                }
            } finally {
                isGenerating = false
            }
        }
    }

    fun stopGeneration() {
        engine?.cancel()
        generation?.cancel()
    }

    fun newChat() {
        stopGeneration()
        messages.clear()
        scamCheckMode = false
        viewModelScope.launch { engineMutex.withLock { withContext(engineDispatcher) { engine?.resetConversation() } } }
    }

    /**
     * Scam Check: describe (type or speak) what a caller/message is saying and get an instant
     * risk verdict. Waits for the model if it's still loading (e.g. opened from a scam alert).
     */
    fun startScamCheck() {
        closeSession(resetChat = false)
        stopGeneration()
        showShield = false
        messages.clear()
        scamCheckMode = true
        messages += ChatMessage(
            nextId++, fromUser = false,
            text = "Scam Check is on. Tell me what the caller or message is saying — type it or tap the mic.\n" +
                "Example: \"He says he is from CBI, my Aadhaar was used for a drugs parcel, and I must stay on video call.\"\n\n" +
                "मुझे बताइए कॉल करने वाला क्या कह रहा है — मैं बताऊँगा यह धोखा है या नहीं।",
        )
        viewModelScope.launch {
            snapshotFlow { modelState }.first { it is ModelState.Ready || it is ModelState.Failed }
            engineMutex.withLock {
                withContext(engineDispatcher) { engine?.resetConversation(SCAM_CHECK_PROMPT, temperature = 0.3) }
            }
        }
    }

    // ------------------------------------------------------------------ Live meeting / call notes

    private fun beginSession(kind: NotesSession.Kind): NotesSession {
        closeSession()
        stopGeneration()
        messages.clear()
        return NotesSession(kind).also { session = it }
    }

    /**
     * Starts a live meeting. Returns true if it records in the background (mic → Gemma audio
     * transcription, inside a foreground service). Returns false when Gemma's audio encoder isn't
     * available: the activity then feeds on-screen speech recognition via [addMeetingSegment].
     * Caller must already hold RECORD_AUDIO.
     */
    fun startMeeting(): Boolean {
        val s = beginSession(NotesSession.Kind.Meeting)
        val e = engine ?: return false
        if (!e.audioSupported) {
            s.status = "Keep the app open: background recording needs Gemma audio, which this phone couldn't load."
            return false
        }
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "mic").apply { deleteRecursively(); mkdirs() }
        val clips = Channel<Triple<File, Int, Boolean>>(Channel.UNLIMITED)
        micClips = clips
        SessionService.start(app, mic = true)
        val recorder = MicRecorder(dir, MIC_CLIP_SECONDS) { wav, startSec, hasSound ->
            clips.trySend(Triple(wav, startSec, hasSound))
        }
        try {
            recorder.start()
        } catch (t: Throwable) {
            SessionService.stop(app)
            s.error = t.message ?: "Couldn't start the microphone"
            s.inputDone = true
            s.finished = true
            return true
        }
        micRecorder = recorder
        s.micActive = true
        s.status = "Recording — transcript appears about every $MIC_CLIP_SECONDS seconds"

        sessionJob = viewModelScope.launch {
            try {
                for ((wav, startSec, hasSound) in clips) {
                    if (hasSound) {
                        val sb = StringBuilder()
                        runCatching {
                            engineMutex.withLock {
                                withContext(engineDispatcher) { e.transcribe(wav.absolutePath).collect { sb.append(it) } }
                            }
                        }.onFailure { if (it is CancellationException) throw it else s.error = "Transcription error: ${it.message}" }
                        val text = sb.toString().trim()
                        if (text.isNotEmpty()) {
                            s.segments += Segment(startSec, text)
                            maybeUpdateNotes(s)
                        }
                    }
                    wav.delete()
                    SessionService.update(
                        app, mic = true,
                        "${NotesSession.fmt(s.elapsedSec())} recorded · ${s.segments.size} lines transcribed",
                    )
                }
                // Recorder stopped and every clip is transcribed.
                s.status = null
                finishMeetingInput()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            }
        }
        return true
    }

    /** Stops the background microphone; remaining clips are still transcribed, then notes are finalized. */
    fun stopMeeting() {
        val recorder = micRecorder ?: return
        micRecorder = null
        recorder.stop()
        session?.micActive = false
        session?.status = "Finishing transcript…"
        val clips = micClips
        viewModelScope.launch(Dispatchers.IO) {
            recorder.awaitStopped()
            clips?.close()
        }
    }

    val isRecordingInBackground: Boolean get() = micRecorder != null

    /** Stop button (in-app or notification) for whichever session is running. */
    fun stopSession() {
        val s = session ?: return
        when {
            s.kind == NotesSession.Kind.Meeting && micRecorder != null -> stopMeeting()
            s.kind == NotesSession.Kind.Recording && !s.inputDone -> {
                // Keep what's been transcribed so far and write final notes from it.
                engine?.cancel()
                sessionJob?.cancel()
                s.inputDone = true
                s.status = null
                maybeUpdateNotes(s)
            }
        }
    }

    fun addMeetingSegment(text: String) {
        val s = session ?: return
        if (s.kind != NotesSession.Kind.Meeting || s.inputDone) return
        s.segments += Segment(s.elapsedSec(), text)
        maybeUpdateNotes(s)
    }

    /** Meeting stopped: fold the remaining transcript into the notes, then mark finished. */
    fun finishMeetingInput() {
        val s = session ?: return
        s.inputDone = true
        maybeUpdateNotes(s)
    }

    /** Meeting: update notes in the background once enough new speech has arrived. */
    private fun maybeUpdateNotes(s: NotesSession) {
        if (s.updatingNotes || session !== s || engine == null) return
        val pending = s.pendingWords()
        val due = pending >= NOTES_EVERY_WORDS ||
            (pending > 0 && System.currentTimeMillis() - s.lastNotesAt >= NOTES_EVERY_MS) ||
            (pending > 0 && s.inputDone)
        if (!due) {
            if (s.inputDone && pending == 0) {
                s.finished = true
                SessionService.stop(getApplication())
            }
            return
        }
        s.updatingNotes = true
        viewModelScope.launch {
            foldPendingIntoNotes(s)
            maybeUpdateNotes(s) // catch up on anything said meanwhile, or finish
        }
    }

    /** Folds up to ~[NOTES_MAX_WORDS] of not-yet-noted transcript into the notes. */
    private suspend fun foldPendingIntoNotes(s: NotesSession) {
        val e = engine ?: return
        var end = s.notedUpTo
        var words = 0
        while (end < s.segments.size && (words < NOTES_MAX_WORDS || end == s.notedUpTo)) {
            words += s.segments[end].text.split(Regex("\\s+")).count(String::isNotBlank)
            end++
        }
        if (end == s.notedUpTo) { s.updatingNotes = false; return }
        val chunk = s.transcriptText(s.notedUpTo, end)
        val prompt = buildString {
            append("Current notes:\n")
            append(s.notes.ifBlank { "(none yet)" })
            append("\n\nNew part of the transcript:\n")
            append(chunk)
            append(
                "\n\nRewrite the notes so they include the new information. Use exactly these sections:\n" +
                    "Summary: 2-4 sentences.\n" +
                    "Key points: short bullet list.\n" +
                    "Decisions: bullet list, or \"None\".\n" +
                    "Action items: bullet list with who/when if mentioned, or \"None\".\n" +
                    "Keep it under 250 words. Output only the notes, no markdown symbols like ** or #."
            )
        }
        s.updatingNotes = true
        s.notesDraft = ""
        try {
            val sb = StringBuilder()
            engineMutex.withLock {
                withContext(engineDispatcher) {
                    e.resetConversation(NotesSession.NOTES_SYSTEM_PROMPT, temperature = 0.3)
                    e.send(prompt).collect { piece ->
                        sb.append(piece)
                        val t = sb.toString()
                        withContext(Dispatchers.Main) { s.notesDraft = t }
                    }
                }
            }
            val result = sb.toString().trim()
            if (result.isNotEmpty()) s.notes = result
            s.error = null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            s.error = "Couldn't update notes: ${t.message}"
        } finally {
            // Advance even on failure so one bad chunk can't cause an endless retry loop.
            s.notedUpTo = end
            s.lastNotesAt = System.currentTimeMillis()
            s.notesDraft = ""
            s.updatingNotes = false
        }
    }

    /**
     * Call recording (shared from the phone's call recorder, or picked in the app):
     * decode → 30 s clips → Gemma transcribes each clip on-device → notes update as it goes.
     */
    fun summarizeRecording(uri: Uri) {
        if (uri.scheme != "content") return
        val app = getApplication<Application>()
        val s = beginSession(NotesSession.Kind.Recording)
        val dir = File(app.cacheDir, "recording").apply { deleteRecursively(); mkdirs() }
        val input = File(dir, "input.audio")
        s.status = "Reading recording…"
        s.progress = 0f
        SessionService.start(app, mic = false)
        sessionJob = viewModelScope.launch {
            try {
                // Copy right away: the permission to read a shared file can expire.
                withContext(Dispatchers.IO) {
                    val src = app.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the recording")
                    src.use { i ->
                        input.outputStream().use { o ->
                            val copied = i.copyTo(o)
                            if (copied > MAX_RECORDING_BYTES) throw IOException("Recording is too large (max 1 GB)")
                        }
                    }
                }
                s.status = "Waiting for the model…"
                snapshotFlow { modelState }.first { it is ModelState.Ready || it is ModelState.Failed }
                val e = engine ?: throw IllegalStateException("The model isn't loaded")
                if (!e.audioSupported) {
                    throw IllegalStateException("This phone couldn't load Gemma's audio encoder, so recordings can't be transcribed.")
                }
                val total = withContext(Dispatchers.IO) { AudioChunker.durationSeconds(input) }
                withContext(Dispatchers.IO) {
                    AudioChunker.decode(input, File(dir, "chunks")) { wav, startSec, hasSound ->
                        withContext(Dispatchers.Main) {
                            s.status = "Listening to the call… " +
                                NotesSession.fmt(startSec) + if (total > 0) " / " + NotesSession.fmt(total) else ""
                        }
                        if (hasSound) {
                            val sb = StringBuilder()
                            engineMutex.withLock {
                                withContext(engineDispatcher) { e.transcribe(wav.absolutePath).collect { sb.append(it) } }
                            }
                            val text = sb.toString().trim()
                            if (text.isNotEmpty()) withContext(Dispatchers.Main) { s.segments += Segment(startSec, text) }
                        }
                        wav.delete()
                        withContext(Dispatchers.Main) {
                            if (total > 0) s.progress = ((startSec + AudioChunker.CHUNK_SECONDS).toFloat() / total).coerceAtMost(1f)
                            SessionService.update(app, mic = false, "${((s.progress ?: 0f) * 100).toInt()}% processed")
                        }
                        // Refresh the notes every couple of minutes of audio so they build up live.
                        if (s.pendingWords() >= RECORDING_NOTES_EVERY_WORDS) {
                            withContext(Dispatchers.Main) { foldPendingIntoNotes(s) }
                        }
                    }
                }
                s.inputDone = true
                s.progress = 1f
                s.status = "Writing final notes…"
                while (s.pendingWords() > 0) foldPendingIntoNotes(s)
                if (s.segments.isEmpty()) s.error = "No speech was found in this recording."
                s.status = null
                s.finished = true
                SessionService.stop(app)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                s.error = t.message ?: "Couldn't process the recording"
                s.status = null
                s.inputDone = true
                s.finished = true
                SessionService.stop(app)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            }
        }
    }

    /** Notes + the most recent transcript that fits comfortably in the context window. */
    private fun sessionMaterial(s: NotesSession): String {
        var from = s.segments.size
        var words = 0
        while (from > 0 && words < CONTEXT_TRANSCRIPT_WORDS) {
            from--
            words += s.segments[from].text.split(Regex("\\s+")).size
        }
        return buildString {
            append("NOTES:\n")
            append(s.notes.ifBlank { "(no notes)" })
            append("\n\nTRANSCRIPT")
            if (from > 0) append(" (last part only)")
            append(":\n")
            append(s.transcriptText(from))
        }
    }

    private fun sessionWhat(s: NotesSession) = if (s.kind == NotesSession.Kind.Meeting) "meeting" else "phone call"

    /** Leaves the notes screen and hands the notes + transcript to the chat as context. */
    fun askAboutSession() {
        val s = session ?: return
        val what = sessionWhat(s)
        val context = LlmEngine.SYSTEM_PROMPT +
            "\n\nThe user wants to discuss a $what. Answer using the notes and transcript below; " +
            "if something isn't in them, say so.\n\n" + sessionMaterial(s)
        closeSession(resetChat = false)
        messages.clear()
        messages += ChatMessage(
            nextId++, fromUser = false,
            text = "I have the notes and transcript of this $what. Ask me anything about it — " +
                "for example \"What did we agree on?\" or \"Write a follow-up message.\"",
        )
        viewModelScope.launch {
            engineMutex.withLock { withContext(engineDispatcher) { engine?.resetConversation(context) } }
        }
    }

    /**
     * Fraud check of a call recording / meeting: Gemma reads the transcript as Scam Check and
     * immediately gives a HIGH / MEDIUM / LOW verdict. Follow-up questions stay in Scam Check mode.
     */
    fun scamCheckSession() {
        val s = session ?: return
        val what = sessionWhat(s)
        val context = SCAM_CHECK_PROMPT + "\n\nThe user recorded a $what and wants to know if it was a scam. " +
            "Base your verdict on this transcript (speech recognition, may contain errors):\n\n" + sessionMaterial(s)
        closeSession(resetChat = false)
        messages.clear()
        scamCheckMode = true
        viewModelScope.launch {
            // Holds the engine lock first, so the send() below runs after the reset.
            engineMutex.withLock {
                withContext(engineDispatcher) { engine?.resetConversation(context, temperature = 0.3) }
            }
        }
        send("Was this $what a scam? Check it for fraud red flags.")
    }

    /** Ends the session (if any) and discards its transcript. */
    fun closeSession(resetChat: Boolean = true) {
        val s = session ?: return
        micRecorder?.stop()
        micRecorder = null
        micClips?.close()
        micClips = null
        SessionService.stop(getApplication())
        sessionJob?.cancel()
        sessionJob = null
        engine?.cancel()
        s.segments.clear()
        session = null
        if (resetChat) newChat()
    }

    private fun update(id: Long, f: (ChatMessage) -> ChatMessage) {
        val i = messages.indexOfFirst { it.id == id }
        if (i >= 0) messages[i] = f(messages[i])
    }

    override fun onCleared() {
        micRecorder?.stop()
        SessionService.stop(getApplication())
        engine?.cancel()
        messages.clear()
        // The engine stays loaded in EngineHolder (not closed here), so reopening the app is instant.
    }

    companion object {
        const val MAX_PROMPT_CHARS = 8000
        private const val MIC_CLIP_SECONDS = 20

        private val SCAM_CHECK_PROMPT = """
You are Scam Check, a fraud-prevention assistant for people in India, running offline on their phone.
The user describes a call, video call, SMS or WhatsApp message they are getting right now. Decide quickly if it is a scam.

Reply in the user's language (English or Hindi), short and clear, in exactly this format:
RISK: HIGH / MEDIUM / LOW
WHY: 2-4 short bullet points naming the red flags you see.
DO NOW: 2-4 short bullet points of concrete actions.

Facts to rely on:
- There is no "digital arrest" in Indian law. Police, CBI, ED, NCB, customs, RBI, TRAI or courts never arrest, interrogate or collect money over phone or video call, and never ask you to stay on a call or keep it secret from family.
- Banks, UPI apps and officials never ask for OTP, PIN, CVV, passwords, or to install screen-sharing apps (AnyDesk, TeamViewer, QuickSupport).
- Entering a UPI PIN or scanning a QR code is only ever needed to PAY money, never to receive it.
- Common scams: fake courier/FedEx parcel with drugs, Aadhaar/SIM misuse, TRAI SIM block, KYC/PAN update, electricity disconnection, customs duty, refunds, lottery/prize, work-from-home tasks, stock/crypto trading groups, loan apps, sextortion, fake relative in trouble, "money sent by mistake".
- Pressure, fear, urgency, threats of arrest, secrecy, and requests to transfer money "for verification" are strong scam signs.

Default actions for HIGH risk: hang up / stop replying; don't pay or share OTP/PIN; call 1930 (National Cyber Crime Helpline) or report at cybercrime.gov.in; report the number on Sanchar Saathi (Chakshu); if money was sent, call 1930 and your bank immediately — the first hours matter.
If unsure, say MEDIUM and tell them how to verify independently (call the official number from the official website, not the one the caller gives).
""".trim()
        private const val NOTES_EVERY_WORDS = 120
        private const val NOTES_EVERY_MS = 60_000L
        private const val NOTES_MAX_WORDS = 900
        private const val RECORDING_NOTES_EVERY_WORDS = 300
        private const val CONTEXT_TRANSCRIPT_WORDS = 1200
        private const val MAX_RECORDING_BYTES = 1L shl 30
    }
}
