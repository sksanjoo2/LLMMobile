package com.gemmamobile.securechat

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Records the microphone (16 kHz mono) and cuts it into fixed-length WAV clips for Gemma.
 * Runs on its own thread, so it keeps going with the screen off while [SessionService] is in the foreground.
 * Clips live in the app's private cache and are deleted as soon as they're transcribed.
 */
class MicRecorder(
    private val outDir: File,
    private val clipSeconds: Int = 20,
    /** Called on the recorder thread with each finished clip, its start offset and whether it has sound. */
    private val onClip: (wav: File, startSec: Int, hasSound: Boolean) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Caller must hold RECORD_AUDIO; start while the app is in the foreground. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        outDir.mkdirs()
        val rate = AudioChunker.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, rate * 2),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable (is another app using it?)" }
        running = true
        rec.startRecording()
        worker = thread(name = "mic-recorder", priority = Thread.MAX_PRIORITY) {
            val clip = ShortArray(rate * clipSeconds)
            var filled = 0
            var index = 0
            val buf = ShortArray(rate / 10)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var off = 0
                    while (off < n) {
                        val take = minOf(n - off, clip.size - filled)
                        System.arraycopy(buf, off, clip, filled, take)
                        filled += take
                        off += take
                        if (filled == clip.size) {
                            emit(clip, filled, index++)
                            filled = 0
                        }
                    }
                }
                // Flush the last partial clip if it's at least a second long.
                if (filled >= rate) emit(clip, filled, index)
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    /** Stops recording; the final partial clip is still delivered. */
    fun stop() {
        running = false
    }

    fun awaitStopped() {
        worker?.join(3000)
    }

    private fun emit(clip: ShortArray, len: Int, index: Int) {
        val pcm = clip.copyOf(len)
        val wav = File(outDir, "mic_$index.wav")
        writeWav(wav, pcm)
        var sum = 0.0
        for (s in pcm) { val v = s / 32768.0; sum += v * v }
        val rms = sqrt(sum / pcm.size)
        onClip(wav, index * clipSeconds, rms > 0.004)
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        val rate = AudioChunker.SAMPLE_RATE
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(bb.array()) }
    }
}
