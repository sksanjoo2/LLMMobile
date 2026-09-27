package com.gemmamobile.securechat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Voice bot built on the phone's own speech engines.
 *
 * Privacy: speech recognition uses ONLY the on-device recognizer (Android 12+), so audio is never
 * sent to a cloud service. Text-to-speech is restricted to voices that don't need a network.
 * Nothing is recorded or stored; audio is only captured while the mic button is active.
 *
 * Must be used from the main thread.
 */
class VoiceController(private val context: Context) {

    enum class Phase { Idle, Listening, Speaking }

    var phase by mutableStateOf(Phase.Idle)
        private set
    /** Live transcript while listening. */
    var partialText by mutableStateOf("")
        private set
    /** Short status/error for the UI; null when there's nothing to show. */
    var status by mutableStateOf<String?>(null)
        private set
    /** Voice mode: speak replies aloud and listen again after each reply (hands-free). */
    var voiceMode by mutableStateOf(false)
        private set
    var ttsAvailable by mutableStateOf(false)
        private set
    private var ttsProblem: String? = "speech engine is still starting"

    /** Called with the final transcript; the activity sends it to the model. */
    var onFinalTranscript: (String) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    // Streaming TTS bookkeeping for the reply currently being spoken.
    private var speakingMessageId = -1L
    private var spokenUpTo = 0
    private var finishedMessageId = -1L
    /** True when the last question was spoken, so its reply is spoken back even outside voice mode. */
    private var speakNextReply = false

    init {
        tts = TextToSpeech(context) { st ->
            main.post {
                if (st == TextToSpeech.SUCCESS) {
                    configureTts()
                } else {
                    ttsProblem = "no text-to-speech engine on this phone (install \"Speech Services by Google\")"
                }
            }
        }
    }

    val sttSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun toggleVoiceMode() {
        voiceMode = !voiceMode
        if (!voiceMode) stopSpeaking()
        status = null
    }

    // ---------------------------------------------------------------- Speech-to-text

    fun startListening() {
        stopSpeaking()
        status = null
        val mode = context.getSystemService(android.media.AudioManager::class.java).mode
        if (mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
            status = "You're on a call — Android keeps the microphone for the call. Please type your message instead."
            return
        }
        if (!sttSupported) {
            status = "On-device speech recognition isn't available on this phone " +
                "(needs Android 12+ with Google speech services). Cloud recognition is disabled for privacy."
            return
        }
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        partialText = ""
        phase = Phase.Listening
        r.startListening(recognizerIntent())
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    /** Stops everything immediately (used on background / lock). */
    fun halt() {
        recognizer?.cancel()
        stopSpeaking()
        if (phase == Phase.Listening) phase = Phase.Idle
        partialText = ""
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { status = null }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults.firstResult()?.let { partialText = it }
        }

        override fun onResults(results: Bundle?) {
            phase = Phase.Idle
            val text = results.firstResult()?.trim().orEmpty()
            partialText = ""
            if (text.isNotEmpty()) {
                speakNextReply = true
                onFinalTranscript(text)
            } else {
                status = "Didn't catch that"
            }
        }

        override fun onError(error: Int) {
            phase = Phase.Idle
            partialText = ""
            status = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — tap the mic to try again"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy, try again"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    requestLanguagePack()
                }
                SpeechRecognizer.ERROR_CLIENT -> null // we cancelled it ourselves
                else -> "Speech recognition error ($error)"
            }
        }
    }

    /** Asks the system to download the offline speech model for the current language (Android 13+). */
    private fun requestLanguagePack(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { recognizer?.triggerModelDownload(recognizerIntent()) }
            return "Downloading the offline speech pack for ${Locale.getDefault().displayLanguage}. Try again in a minute."
        }
        return "Offline speech for ${Locale.getDefault().displayLanguage} isn't installed. " +
            "Install it in Settings → Google → Voice → Offline speech recognition."
    }

    private fun Bundle?.firstResult(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    // ---------------------------------------------------------------- Text-to-speech

    private fun configureTts() {
        val t = tts ?: return
        val locale = Locale.getDefault()
        if (t.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.US)
        // Only use voices that synthesize on the device.
        val lang = t.voice?.locale?.language ?: locale.language
        val local = runCatching { t.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.locale.language == lang && !it.isNotInstalled() }
            .maxByOrNull { it.quality }
        if (local != null) t.voice = local
        // Some engines (e.g. Samsung TTS) return null for the current voice; that still speaks locally.
        val current = runCatching { t.voice }.getOrNull()
        ttsAvailable = current == null || !current.isNetworkConnectionRequired
        ttsProblem = if (ttsAvailable) null else "only online voices are installed for ${locale.displayLanguage}"
        t.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )

        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                when {
                    // A single tapped message finished: just go idle, don't start listening.
                    utteranceId == "${END_PREFIX}once" -> main.post { if (phase == Phase.Speaking) phase = Phase.Idle }
                    utteranceId?.startsWith(END_PREFIX) == true -> main.post { onReplySpoken() }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onError(utteranceId, -1)
            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post {
                    if (phase == Phase.Speaking) phase = Phase.Idle
                    status = "Text-to-speech error ($errorCode)"
                }
            }
        })
    }

    private fun Voice.isNotInstalled() =
        features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

    /**
     * Feed the reply text as it streams in. Complete sentences are queued for speech right away,
     * so the bot starts talking before the model has finished.
     */
    fun onReplyProgress(messageId: Long, text: String, done: Boolean) {
        if (!(voiceMode || speakNextReply) || messageId == finishedMessageId) return
        val t = tts ?: return
        if (!ttsAvailable) {
            status = "Text-to-speech isn't ready: " + (ttsProblem ?: "no offline voice installed")
            return
        }
        if (messageId != speakingMessageId) {
            speakingMessageId = messageId
            spokenUpTo = 0
        }
        val pending = text.substring(spokenUpTo.coerceAtMost(text.length))
        val cut = if (done) pending.length else lastSentenceEnd(pending)
        if (cut > 0) {
            val chunk = cleanForSpeech(pending.substring(0, cut))
            if (chunk.isNotBlank()) {
                phase = Phase.Speaking
                t.speak(chunk, TextToSpeech.QUEUE_ADD, null, "reply-$messageId-$spokenUpTo")
            }
            spokenUpTo += cut
        }
        if (done) {
            finishedMessageId = messageId
            speakNextReply = false
            if (phase == Phase.Speaking) {
                t.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "$END_PREFIX$messageId")
            } else {
                onReplySpoken()
            }
        }
    }

    private fun onReplySpoken() {
        if (phase == Phase.Speaking) phase = Phase.Idle
        // Hands-free: listen for the next question automatically.
        if (voiceMode && phase == Phase.Idle) startListening()
    }

    /** Reads one message aloud (tap on a reply bubble). Tapping again while it speaks stops it. */
    fun speakOnce(text: String) {
        val t = tts ?: return
        if (phase == Phase.Speaking) { stopSpeaking(); return }
        if (!ttsAvailable) {
            status = "Text-to-speech isn't ready: " + (ttsProblem ?: "no offline voice installed")
            return
        }
        recognizer?.cancel()
        status = null
        phase = Phase.Speaking
        t.speak(cleanForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, "once")
        t.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "${END_PREFIX}once")
    }

    fun stopSpeaking() {
        tts?.stop()
        if (speakingMessageId >= 0) finishedMessageId = speakingMessageId
        if (phase == Phase.Speaking) phase = Phase.Idle
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun lastSentenceEnd(s: String): Int {
        var end = 0
        for (i in s.indices) {
            val c = s[i]
            val boundary = (c == '.' || c == '!' || c == '?' || c == '।' || c == '\n') &&
                (i + 1 == s.length || s[i + 1].isWhitespace())
            // Require a following char so "3." isn't cut mid-number while still streaming.
            if (boundary && i + 1 < s.length) end = i + 1
        }
        return end
    }

    /** Remove markdown so the voice doesn't read out asterisks, hashes and backticks. */
    private fun cleanForSpeech(s: String) = s
        .replace(Regex("```[\\s\\S]*?```"), " code block omitted. ")
        .replace(Regex("[*_#`>|~]"), "")
        .replace(Regex("\\[(.*?)]\\((.*?)\\)"), "$1")
        .trim()

    private companion object {
        const val END_PREFIX = "end-"
    }
}
