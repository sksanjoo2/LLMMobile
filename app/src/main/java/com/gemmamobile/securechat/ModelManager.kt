package com.gemmamobile.securechat

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The models this app accepts. Each is pinned to an exact Hugging Face commit and SHA-256,
 * so a corrupted or tampered file is never loaded.
 */
enum class ModelSpec(
    val title: String,
    val blurb: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    E2B(
        "Gemma 4 E2B — Fast",
        "About 2× faster, 2.6 GB. Best for most phones.",
        "gemma-4-E2B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/" +
            "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm",
        "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        2_588_147_712L,
    ),
    E4B(
        "Gemma 4 E4B — Smart",
        "Better answers but slower, 3.7 GB. For phones with 8 GB+ RAM.",
        "gemma-4-E4B-it.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/" +
            "2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it.litertlm",
        "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
        3_659_530_240L,
    );

    val sizeGb: String get() = "%.1f GB".format(sizeBytes / 1_000_000_000.0)
}

/**
 * Owns the on-device model files.
 *
 * Models live in the app's private, never-backed-up storage (noBackupFilesDir), which no
 * other app can read. A file is only accepted after its size and SHA-256 match a [ModelSpec].
 */
class ModelManager(context: Context) {

    private val dir: File = context.noBackupFilesDir
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    private val partFile = File(dir, "model.part")

    fun file(spec: ModelSpec) = File(dir, spec.fileName)
    private fun marker(spec: ModelSpec) = File(dir, "model-${spec.name}.verified")

    /** The model the user chose. Defaults to whatever is installed, else the fast one. */
    var selected: ModelSpec
        get() = prefs.getString("selected", null)?.let { runCatching { ModelSpec.valueOf(it) }.getOrNull() }
            ?: ModelSpec.entries.firstOrNull { isReady(it) }
            ?: ModelSpec.E2B
        set(v) = prefs.edit().putString("selected", v.name).apply()

    /** Cheap check used at startup; the expensive hash was done once when the file was installed. */
    fun isReady(spec: ModelSpec): Boolean {
        val f = file(spec)
        if (!f.isFile || f.length() != spec.sizeBytes) return false
        val expected = markerFor(spec, f)
        if (marker(spec).isFile && marker(spec).readText() == expected) return true
        // v1.0–1.2 stored the E4B marker as "model.verified".
        val legacy = File(dir, "model.verified")
        if (spec == ModelSpec.E4B && legacy.isFile && legacy.readText() == expected) {
            marker(spec).writeText(expected)
            legacy.delete()
            return true
        }
        return false
    }

    fun installed(): List<ModelSpec> = ModelSpec.entries.filter { isReady(it) }

    fun delete(spec: ModelSpec) {
        marker(spec).delete()
        file(spec).delete()
        partFile.delete()
    }

    /**
     * Copies a user-picked .litertlm file into private storage, verifying it on the way.
     * Works out which model it is from the hash, and returns it.
     */
    suspend fun importFrom(context: Context, uri: Uri, onProgress: (Float) -> Unit): ModelSpec {
        val maxSize = ModelSpec.entries.maxOf { it.sizeBytes }
        ensureFreeSpace(maxSize)
        partFile.delete()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the selected file")
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { src ->
            partFile.outputStream().use { dst -> copy(src, dst, digest, 0L, maxSize, onProgress) }
        }
        val hash = digest.digest().hex()
        val spec = ModelSpec.entries.firstOrNull { it.sha256.equals(hash, true) && it.sizeBytes == partFile.length() }
        if (spec == null) {
            partFile.delete()
            throw SecurityException(
                "This file isn't a supported model (SHA-256 mismatch). Use the official " +
                    ModelSpec.entries.joinToString(" or ") { it.fileName } + " from litert-community."
            )
        }
        install(spec)
        return spec
    }

    /**
     * Downloads the pinned model over HTTPS, resuming a partial download if one exists.
     * Only works in the "online" flavor (the offline one has no INTERNET permission).
     */
    suspend fun download(spec: ModelSpec, onProgress: (Float) -> Unit) {
        check(BuildConfig.ALLOW_DOWNLOAD) { "Downloading is disabled in this build" }
        // A partial file from a different model can't be resumed.
        val partSpec = prefs.getString("part", null)
        var existing = if (partFile.isFile && partSpec == spec.name) partFile.length() else 0L
        if (existing == 0L || existing >= spec.sizeBytes) {
            partFile.delete()
            existing = 0L
        }
        prefs.edit().putString("part", spec.name).apply()
        ensureFreeSpace(spec.sizeBytes - existing)

        val conn = URL(spec.url).openConnection() as HttpsURLConnection
        try {
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "GemmaSecureChat/${BuildConfig.VERSION_NAME}")
            if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

            val code = conn.responseCode
            // HttpsURLConnection only follows https->https redirects, but double-check the final hop.
            if (conn.url.protocol != "https") throw IOException("Refusing non-HTTPS redirect")
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("Download failed: HTTP $code")
            }
            if (!append) existing = 0L

            conn.inputStream.use { src ->
                java.io.FileOutputStream(partFile, append).use { dst ->
                    copy(src, dst, null, existing, spec.sizeBytes, onProgress)
                }
            }
        } finally {
            conn.disconnect()
        }

        // The file may have been assembled across several resumed sessions, so hash it from disk.
        val digest = MessageDigest.getInstance("SHA-256")
        partFile.inputStream().use { src -> copy(src, NullOutputStream, digest, 0L, spec.sizeBytes, onProgress) }
        if (partFile.length() != spec.sizeBytes || !digest.digest().hex().equals(spec.sha256, true)) {
            partFile.delete()
            throw SecurityException("Model verification failed (size or SHA-256 mismatch). Please download again.")
        }
        install(spec)
    }

    private fun install(spec: ModelSpec) {
        val target = file(spec)
        target.delete()
        if (!partFile.renameTo(target)) throw IOException("Could not move model into place")
        marker(spec).writeText(markerFor(spec, target))
        prefs.edit().remove("part").apply()
    }

    private suspend fun copy(
        src: InputStream,
        dst: OutputStream,
        digest: MessageDigest?,
        startAt: Long,
        expectedSize: Long,
        onProgress: (Float) -> Unit,
    ) {
        val buf = ByteArray(1 shl 20)
        var total = startAt
        var lastReported = -1
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = src.read(buf)
            if (n < 0) break
            dst.write(buf, 0, n)
            digest?.update(buf, 0, n)
            total += n
            if (total > expectedSize) throw IOException("File is larger than the expected model")
            val pct = (total * 1000 / expectedSize).toInt()
            if (pct != lastReported) {
                lastReported = pct
                onProgress(pct / 1000f)
            }
        }
    }

    private fun ensureFreeSpace(needed: Long) {
        val margin = 200L * 1024 * 1024
        if (dir.usableSpace < needed + margin) {
            val gb = (needed + margin) / 1_000_000_000.0
            throw IOException("Not enough free storage. About %.1f GB is required.".format(gb))
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    private fun markerFor(spec: ModelSpec, f: File) = "${spec.sha256}:${f.length()}:${f.lastModified()}"
}
