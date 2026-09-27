package com.gemmamobile.securechat

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide home for the loaded model, so closing and reopening the app screen doesn't
 * reload several GB from storage. LiteRT-LM isn't thread-safe: use [dispatcher] + [mutex].
 */
object EngineHolder {
    val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    val mutex = Mutex()
    var engine: LlmEngine? = null
}

/**
 * Thin wrapper over LiteRT-LM. Everything runs in-process on the device; there is no network code here.
 * Not thread-safe: callers must use a single dispatcher (see ChatViewModel).
 */
class LlmEngine(private val context: Context, val modelPath: String) : AutoCloseable {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    var backendName: String = ""
        private set
    /** Whether the audio encoder loaded (needed to transcribe call recordings). */
    var audioSupported: Boolean = false
        private set

    /**
     * Blocking; takes several seconds. Speed matters most, so the order is: GPU with audio,
     * GPU text-only, then CPU. Speculative decoding (1.3–2× faster replies) is tried first.
     */
    @OptIn(ExperimentalApi::class)
    fun initialize() {
        val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)
        // A GPU crash flag from an older app version shouldn't force CPU forever.
        if (prefs.getInt(KEY_FLAG_VERSION, 0) != BuildConfig.VERSION_CODE) {
            prefs.edit().putBoolean(KEY_GPU_IN_PROGRESS, false).putInt(KEY_FLAG_VERSION, BuildConfig.VERSION_CODE).commit()
        }
        // If a previous GPU init crashed the process natively, the flag is still set: skip GPU.
        val gpuCrashed = prefs.getBoolean(KEY_GPU_IN_PROGRESS, false)
        val gpuOptions = if (gpuCrashed) listOf(false) else listOf(true, false)
        // (useGpu, withAudio)
        val attempts = gpuOptions.flatMap { listOf(it to true, it to false) }

        var lastError: Throwable? = null
        for (speculative in listOf(true, null)) {
            ExperimentalFlags.enableSpeculativeDecoding = speculative
            for ((isGpu, withAudio) in attempts) {
                if (isGpu) prefs.edit().putBoolean(KEY_GPU_IN_PROGRESS, true).commit()
                val candidate = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = if (isGpu) Backend.GPU() else Backend.CPU(),
                        audioBackend = if (withAudio) Backend.CPU() else null,
                        maxNumTokens = MAX_TOKENS,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                )
                try {
                    candidate.initialize()
                    if (isGpu) prefs.edit().putBoolean(KEY_GPU_IN_PROGRESS, false).commit()
                    engine = candidate
                    backendName = (if (isGpu) "GPU" else "CPU") + if (speculative == true) " · turbo" else ""
                    audioSupported = withAudio
                    conversation = candidate.createConversation(conversationConfig())
                    return
                } catch (t: Throwable) {
                    lastError = t
                    runCatching { candidate.close() }
                    if (isGpu) prefs.edit().putBoolean(KEY_GPU_IN_PROGRESS, false).commit()
                }
            }
        }
        throw IllegalStateException("Could not load the model: ${lastError?.message}", lastError)
    }

    /** Streams the reply as text chunks. */
    fun send(prompt: String): Flow<String> {
        val conv = conversation ?: error("Engine not initialized")
        return conv.sendMessageAsync(prompt).map { it.text() }
    }

    fun cancel() {
        runCatching { conversation?.cancelProcess() }
    }

    /**
     * Transcribes a short (≤30 s) 16 kHz mono WAV clip with Gemma's built-in audio encoder.
     * Uses a fresh conversation so clips don't accumulate in the context window.
     */
    fun transcribe(wavPath: String): Flow<String> {
        check(audioSupported) { "Audio input isn't available on this device" }
        resetConversation(TRANSCRIBE_PROMPT, temperature = 0.1, maxOutput = 400)
        val conv = conversation ?: error("Engine not initialized")
        val contents = Contents.of(
            Content.AudioFile(wavPath),
            Content.Text("Transcribe this audio."),
        )
        return conv.sendMessageAsync(contents).map { it.text() }
    }

    /** Drops all conversation state (the KV cache) and starts fresh with the given instructions. */
    fun resetConversation(
        systemInstruction: String = SYSTEM_PROMPT,
        temperature: Double = 0.7,
        maxOutput: Int = MAX_REPLY_TOKENS,
    ) {
        val e = engine ?: return
        runCatching { conversation?.close() }
        conversation = e.createConversation(conversationConfig(systemInstruction, temperature, maxOutput))
    }

    override fun close() {
        runCatching { conversation?.close() }
        runCatching { engine?.close() }
        conversation = null
        engine = null
    }

    private fun conversationConfig(
        systemInstruction: String = SYSTEM_PROMPT,
        temperature: Double = 0.7,
        maxOutput: Int = MAX_REPLY_TOKENS,
    ) = ConversationConfig(
        systemInstruction = Contents.of(systemInstruction),
        samplerConfig = SamplerConfig(topK = if (temperature <= 0.1) 1 else 40, topP = 0.95, temperature = temperature),
        maxOutputToken = maxOutput,
        // Gemma 4 can "think" (write hidden reasoning) before answering. That multiplies response
        // time on a phone, so it's explicitly off.
        thinkingConfig = ThinkingConfig(enableThinking = false),
    )

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    companion object {
        const val MAX_TOKENS = 4096
        const val MAX_REPLY_TOKENS = 1024
        private const val KEY_FLAG_VERSION = "gpu_flag_version"
        const val SYSTEM_PROMPT =
            "You are a helpful, accurate assistant running fully offline on the user's phone. " +
                "Read the user's question carefully and answer exactly what was asked, completely and correctly. " +
                "Reply in the same language the user writes in (English, Hindi or Hinglish). " +
                "Start with the answer itself; skip greetings and filler. " +
                "If the question is unclear, ask one short clarifying question. " +
                "If you don't know something, say so instead of guessing."
        private const val TRANSCRIBE_PROMPT =
            "You are a speech-to-text engine. Write exactly what is said in the audio, in the language " +
                "it is spoken. Output only the transcript, no commentary. If there is no clear speech, output nothing."
        private const val KEY_GPU_IN_PROGRESS = "gpu_init_in_progress"
    }
}
