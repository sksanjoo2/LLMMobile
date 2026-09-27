package com.gemmamobile.securechat

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decodes any audio format Android supports (call recorders use m4a/AAC, AMR, MP3, OGG/Opus, WAV)
 * into 16 kHz mono 16-bit WAV clips, which is what Gemma's audio encoder expects.
 * Uses only the platform MediaCodec decoders; nothing leaves the device.
 */
object AudioChunker {

    const val SAMPLE_RATE = 16_000
    const val CHUNK_SECONDS = 30

    /** Duration of the audio in seconds, or 0 if unknown. */
    fun durationSeconds(file: File): Int {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.mime(it).startsWith("audio/") } ?: return 0
            val f = ex.getTrackFormat(track)
            if (f.containsKey(MediaFormat.KEY_DURATION)) (f.getLong(MediaFormat.KEY_DURATION) / 1_000_000).toInt() else 0
        } catch (_: Exception) {
            0
        } finally {
            ex.release()
        }
    }

    /**
     * Streams [input] as [CHUNK_SECONDS]-second WAV files into [outDir]. [onChunk] gets each file,
     * its start time in seconds and whether it contains speech-level audio (silent clips are flagged
     * so the caller can skip them — models tend to invent text for silence).
     */
    suspend fun decode(
        input: File,
        outDir: File,
        onChunk: suspend (wav: File, startSec: Int, hasSound: Boolean) -> Unit,
    ) {
        outDir.mkdirs()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.mime(it).startsWith("audio/") }
                ?: throw IOException("No audio track found in this file")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)

            var srcRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(inFormat, null, null, 0)
            codec.start()

            // Mono samples at the source rate for the chunk being assembled.
            var mono = FloatArray(srcRate * CHUNK_SECONDS)
            var monoLen = 0
            var chunkIndex = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            suspend fun emit(final: Boolean) {
                val minSamples = if (final) srcRate else srcRate * CHUNK_SECONDS
                if (monoLen < minSamples) return
                val pcm = resample(mono, monoLen, srcRate)
                val wav = File(outDir, "chunk_$chunkIndex.wav")
                writeWav(wav, pcm)
                onChunk(wav, chunkIndex * CHUNK_SECONDS, rms(pcm) > SILENCE_RMS)
                chunkIndex++
                monoLen = 0
            }

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        mono = mono.copyOf(maxOf(monoLen, srcRate * CHUNK_SECONDS))
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        for (i in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) {
                                sum += if (floatPcm) out.float else out.short / 32768f
                            }
                            mono[monoLen++] = sum / channels
                            if (monoLen == mono.size) emit(final = false)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            emit(final = true)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun MediaExtractor.mime(track: Int) = getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()

    /** Linear-interpolation resample to 16 kHz, converted to 16-bit PCM. */
    private fun resample(src: FloatArray, len: Int, srcRate: Int): ShortArray {
        val outLen = (len.toLong() * SAMPLE_RATE / srcRate).toInt()
        val ratio = srcRate.toDouble() / SAMPLE_RATE
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(len - 1)
            val i1 = (i0 + 1).coerceAtMost(len - 1)
            val frac = (pos - i0).toFloat()
            val v = src[i0] + (src[i1] - src[i0]) * frac
            (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }

    private fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) { val v = s / 32768.0; sum += v * v }
        return sqrt(sum / pcm.size)
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        bb.put("data".toByteArray()).putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        RandomAccessFile(file, "rw").use { it.setLength(0); it.write(bb.array()) }
    }

    private const val SILENCE_RMS = 0.004
}
