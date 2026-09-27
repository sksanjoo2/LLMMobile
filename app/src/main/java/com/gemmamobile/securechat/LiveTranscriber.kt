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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Continuous, real-time transcription of the microphone for live meetings.
 *
 * Android's recognizer handles one utterance at a time, so this restarts it after every result
 * to keep listening until [stop] is called. On-device recognizer only: audio never leaves the phone.
 * Must be used from the main thread.
 */
class LiveTranscriber(private val context: Context) {

    var running by mutableStateOf(false)
        private set
    var partial by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Receives each finished sentence/utterance. */
    var onSegment: (String) -> Unit = {}

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var consecutiveFailures = 0

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    fun start() {
        error = null
        if (!supported) {
            error = "On-device speech recognition isn't available on this phone (needs Android 12+)."
            return
        }
        if (running) return
        running = true
        consecutiveFailures = 0
        listen()
    }

    /** Stops listening; any words heard so far are delivered as a final segment. */
    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        val tail = partial.trim()
        partial = ""
        recognizer?.cancel()
        if (tail.isNotEmpty()) onSegment(tail)
    }

    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!running || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Let people pause between sentences without the recognizer giving up too early.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        r.startListening(intent)
    }

    private fun restartSoon(delayMs: Long = 150) {
        if (running) main.postDelayed({ listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
        }

        override fun onResults(results: Bundle?) {
            consecutiveFailures = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            partial = ""
            if (text.isNotEmpty()) onSegment(text)
            restartSoon()
        }

        override fun onError(code: Int) {
            if (!running) return
            val fatal = when (code) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    "Offline speech for ${Locale.getDefault().displayLanguage} isn't installed " +
                        "(Settings → Google → Voice → Offline speech recognition)"
                else -> null
            }
            // Silence / no-match are normal in a meeting: just keep listening.
            val silence = code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!silence) consecutiveFailures++
            if (fatal == null && consecutiveFailures < 10) {
                partial.trim().takeIf { it.isNotEmpty() }?.let(onSegment)
                partial = ""
                restartSoon(if (code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 500 else 150)
                return
            }
            running = false
            partial = ""
            error = fatal ?: "Speech recognition keeps failing (error $code). Tap Resume to try again."
        }
    }
}
