package com.voicerewriter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Shared file downloader for the on-device models. The models are large (the Parakeet encoder
 * alone is ~622MB), so the things that matter are resuming and not trusting a file just because
 * it's big.
 *
 * What it does that the previous per-manager loops didn't:
 *
 *  - **Resumes.** A partial `.part` is continued with a `Range` request instead of deleted, so
 *    losing the network at 95% costs seconds rather than 600MB. `If-Range` is sent with the
 *    stored validator, so if the file changed upstream the server sends a plain 200 and we
 *    restart cleanly rather than splicing two different files together.
 *  - **Checks the size it was promised**, rather than a hand-picked "big enough" threshold. A
 *    connection that drops cleanly mid-body used to produce a truncated model that passed
 *    validation and then failed at load time.
 *  - **Verifies the hash when the server gives us one.** On Hugging Face the content SHA256
 *    arrives as `X-Linked-Etag`, and only for LFS-backed files. The plain `ETag` on the CDN
 *    response is a *different* 64-hex value that is NOT the file's hash — checking against it
 *    rejects every large model. (Confirmed by hand: for decoder.int8.onnx the real sha256 is
 *    b6bb6496… which matches `X-Linked-Etag`, while `ETag` reads a793c390….) So `ETag` is used
 *    only for what it's for, `If-Range`, and the hash check reads `X-Linked-Etag`.
 *  - **Checks free space first**, so a doomed download fails immediately instead of after ten
 *    minutes of writing.
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"

    /** Hugging Face returns a 64-hex SHA256 for LFS files, and a 40-hex git SHA1 for the rest. */
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /** Headroom kept free so a download can't fill the disk to zero. */
    private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024

    private const val BUFFER = 1 shl 16

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Fetch [url] into [target], resuming any previous partial attempt. [onProgress] reports
     * 0f..1f over the whole file, including bytes carried over from a previous attempt.
     *
     * Throws [IllegalStateException] on any failure, leaving the `.part` in place so the next
     * call can pick up where this one stopped.
     */
    suspend fun fetch(url: String, target: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val part = File(target.parentFile, "${target.name}.part")
            val validatorFile = File(target.parentFile, "${target.name}.etag")
            // The content hash only comes back on the initial 200; HF's 206 responses omit it.
            // Persisting it means a download that finishes across several resumes is still
            // verified, instead of silently falling back to a length check.
            val hashFile = File(target.parentFile, "${target.name}.sha256")

            var have = if (part.exists()) part.length() else 0L
            val validator = if (have > 0) validatorFile.takeIf { it.isFile }?.readText() else null
            if (have > 0 && validator == null) {
                // No validator recorded, so we can't prove the partial belongs to this file.
                part.delete(); have = 0
            }

            val request = Request.Builder().url(url)
                // OkHttp would otherwise add transparent gzip, which nulls out Content-Length
                // and makes both progress and the size check meaningless.
                .header("Accept-Encoding", "identity")
                .apply {
                    if (have > 0 && validator != null) {
                        header("Range", "bytes=$have-")
                        header("If-Range", validator)
                    }
                }
                .build()

            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    throw IllegalStateException("Download failed for ${target.name}: HTTP ${res.code}")
                }
                // 206 continues the partial; 200 means the server ignored the range (or the file
                // changed under If-Range), so the old bytes are worthless.
                val resuming = res.code == 206
                Log.i(TAG, "${target.name}: HTTP ${res.code}, had ${have}B, ${if (resuming) "resuming" else "starting over"}")
                if (!resuming && have > 0) {
                    part.delete(); have = 0
                }

                val body = res.body ?: throw IllegalStateException("Empty response for ${target.name}")
                val remaining = body.contentLength().takeIf { it > 0 }
                val total = remaining?.plus(have)

                // Opaque validator for a future If-Range; NOT a content hash (see the class doc).
                res.header("ETag")?.trim('"')?.let { validatorFile.writeText(it) }
                linkedEtag(res)?.trim('"')?.lowercase()?.let { hashFile.writeText(it) }
                val expectedHash = hashFile.takeIf { it.isFile }?.readText()?.trim()?.lowercase()

                if (total != null) {
                    val free = (target.parentFile?.usableSpace ?: Long.MAX_VALUE)
                    if (free < total - have + FREE_SPACE_MARGIN) {
                        throw IllegalStateException(
                            "Not enough space for ${target.name}: needs ${(total - have) / 1_000_000}MB"
                        )
                    }
                }

                var written = have
                body.byteStream().use { input ->
                    java.io.FileOutputStream(part, /* append = */ resuming).use { output ->
                        val buf = ByteArray(BUFFER)
                        var lastPct = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            if (total != null) {
                                val pct = ((written * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                            }
                        }
                        output.fd.sync()
                    }
                }

                // A cleanly-closed connection mid-body is indistinguishable from success at the
                // stream level, so compare against what the server said it would send.
                if (total != null && written != total) {
                    throw IllegalStateException(
                        "${target.name} is incomplete: got $written of $total bytes"
                    )
                }

                if (expectedHash != null && SHA256_HEX.matches(expectedHash)) {
                    val actual = sha256(part)
                    if (actual != expectedHash) {
                        // Discard rather than resume: whatever is on disk is not this file, so
                        // continuing from it would splice garbage forever.
                        part.delete(); validatorFile.delete(); hashFile.delete()
                        throw IllegalStateException("${target.name} failed its checksum; discarded")
                    }
                    Log.i(TAG, "${target.name}: sha256 verified")
                } else {
                    Log.i(TAG, "${target.name}: no content hash offered, size-checked only")
                }
            }

            if (!part.renameTo(target)) throw IllegalStateException("Couldn't finalize ${target.name}")
            Log.i(TAG, "${target.name}: complete (${target.length()}B)")
            validatorFile.delete()
            hashFile.delete()
            onProgress(1f)
        }

    /**
     * Hugging Face puts the content SHA256 on its own `302`, not on the CDN `200` it redirects
     * to, and OkHttp follows redirects transparently — so reading the header off the final
     * response alone always comes back null. Walk back up the chain.
     */
    private fun linkedEtag(res: Response): String? {
        var cur: Response? = res
        while (cur != null) {
            cur.header("X-Linked-Etag")?.let { return it }
            cur = cur.priorResponse
        }
        return null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    /**
     * Delete download leftovers belonging to models the app no longer offers, e.g. a half-fetched
     * blob from a build where a different model was selectable. Nothing cleaned these up before,
     * so an abandoned attempt could sit on disk indefinitely.
     *
     * Sidecars for *current* models are deliberately kept: they are exactly what makes a resume
     * possible, and deleting them would reintroduce the bug this class exists to fix.
     */
    fun sweepOrphans(context: Context) {
        val root = File(context.filesDir, "models")
        if (!root.isDirectory) return
        val known = buildSet {
            addAll(ParakeetModelManager.bundleFiles())
            LlmModelManager.MODELS.forEach { add(it.fileName) }
            WhisperModelManager.MODELS.forEach { add(it.fileName) }
        }
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val suffix = SIDECARS.firstOrNull { f.name.endsWith(it) } ?: return@forEach
            if (f.name.removeSuffix(suffix) !in known && f.delete()) {
                Log.i(TAG, "swept orphan ${f.name}")
            }
        }
    }

    private val SIDECARS = listOf(".part", ".etag", ".sha256")
}
