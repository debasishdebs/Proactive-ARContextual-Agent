package com.rokid.context.work

import android.content.Context
import com.rokid.context.core.log.PcLog
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.rokid.context.ai.Http
import com.rokid.context.ai.local.LocalModel
import com.rokid.context.ai.local.LocalModels
import com.rokid.context.ai.local.ModelFile
import com.rokid.context.ai.local.ModelFileVerifier
import com.rokid.context.data.models.LocalModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads one catalog model to filesDir/models/<file>.part, resuming through HTTP Range, then verifies size and SHA-256
 * and atomically renames it into place (spec section 4). Logs carry only the model id and a reason code.
 */
class LocalModelDownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private sealed interface Outcome {
        data object Done : Outcome
        data object Retry : Outcome
        data class Fail(val message: String, val reason: String) : Outcome
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_MODEL_ID).orEmpty()
        val model = resolve(id) ?: return fail(id, MSG_FAILED, "unknown_model")
        if (model.files.any { !it.url.startsWith("https://") }) return fail(id, MSG_FAILED, "not_https")
        val outcome = try {
            // One writer per model file: a replacement waits until the stopped worker has let go of the .part.
            withContext(Dispatchers.IO) { downloadAll(model) }
        } catch (e: IOException) {
            Outcome.Retry.also { log(id, "io") }
        }
        return when (outcome) {
            Outcome.Done -> Result.success().also { log(id, "done") }
            Outcome.Retry -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) fail(id, MSG_FAILED, "gave_up") else Result.retry()
            is Outcome.Fail -> fail(id, outcome.message, outcome.reason)
        }
    }

    /** Each file of the bundle in turn (existing verified files are skipped); the first non-Done outcome stops the run. */
    private suspend fun downloadAll(model: LocalModel): Outcome {
        val total = model.totalBytes
        // Space for the WHOLE bundle before the first byte: what is still missing (verified files and kept .part bytes excluded).
        val dir = LocalModelStore.modelsDir(applicationContext).apply { mkdirs() }
        val missing = model.files.sumOf { f ->
            val target = File(dir, f.file)
            if (target.isFile && ModelFileVerifier.verify(target, f.bytes, f.sha256)) 0L
            else (f.bytes - partFile(target).length().coerceAtMost(f.bytes)).coerceAtLeast(0L)
        }
        if (missing > 0 && freeBytes(dir) < (missing * SPACE_FACTOR).toLong()) return Outcome.Fail(MSG_NO_SPACE, "no_space")
        var before = 0L
        for (f in model.files) {
            // One writer per model file: a replacement waits until the stopped worker has let go of the .part.
            val outcome = writers.computeIfAbsent(f.file) { Mutex() }.withLock { download(model.id, f, before, total) }
            if (outcome != Outcome.Done) return outcome
            before += f.bytes
        }
        return Outcome.Done
    }

    private suspend fun download(id: String, model: ModelFile, before: Long, total: Long): Outcome {
        val dir = LocalModelStore.modelsDir(applicationContext).apply { mkdirs() }
        val target = File(dir, model.file)
        val part = partFile(target)
        if (target.isFile && ModelFileVerifier.verify(target, model.bytes, model.sha256)) return Outcome.Done
        if (part.length() > model.bytes) part.delete()

        val have = part.length()
        if (have < model.bytes) {
            val request = Request.Builder().url(model.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            val call = client().newCall(request)
            cancelOnStop(call) { transfer(call, id, model, part, have, before, total) }?.let { return it }
        }

        if (stopped()) return Outcome.Retry
        if (!ModelFileVerifier.verify(part, model.bytes, model.sha256)) {
            part.delete()
            return Outcome.Fail(MSG_NOT_VERIFIED, "verify_mismatch")
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return Outcome.Done
    }

    /** Stopped by WorkManager, or the coroutine cancelled (Cancel, REPLACE): no further byte may reach the .part. */
    private suspend fun stopped() = isStopped || !currentCoroutineContext().isActive

    /** Cancels [call] the moment this worker is stopped, so a read blocked on a slow link ends at once. */
    private suspend fun <T> cancelOnStop(call: Call, block: suspend () -> T): T = coroutineScope {
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { block() } finally { watcher.cancel() }
    }

    /** Appends the body to [part]; null when it ended and the .part is ready to verify, else the outcome. */
    private suspend fun transfer(call: Call, id: String, model: ModelFile, part: File, from: Long, before: Long, total: Long): Outcome? {
        var have = from
        call.execute().use { response ->
            if (!response.request.url.isHttps) return Outcome.Fail(MSG_FAILED, "not_https")
            val append = when (response.code) {
                206 -> {
                    if (rangeStart(response.header("Content-Range")) != have) { part.delete(); return Outcome.Retry }
                    true
                }
                200 -> false.also { have = 0 }
                416 -> null
                408, 429, in 500..599 -> return Outcome.Retry.also { log(id, "http_${response.code}") }
                else -> return Outcome.Fail(MSG_FAILED, "http_${response.code}")
            }
            if (append != null) {
                val body = response.body ?: return Outcome.Retry
                // Unbuffered, so every chunk that arrived is on disk when the connection drops.
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(BUFFER)
                        var lastReported = -1
                        while (true) {
                            if (stopped()) return Outcome.Retry
                            val n = input.read(buf)
                            if (n < 0) break
                            if (stopped()) return Outcome.Retry // stopped while the read was blocked: drop the chunk
                            if (have + n > model.bytes) { part.delete(); return Outcome.Fail(MSG_NOT_VERIFIED, "too_long") }
                            out.write(buf, 0, n)
                            have += n
                            val percent = ((before + have) * 100 / total).toInt()
                            if (percent != lastReported) {
                                lastReported = percent
                                setProgress(workDataOf(KEY_PROGRESS to (before + have).toFloat() / total))
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun fail(id: String, message: String, reason: String): Result {
        log(id, reason)
        return Result.failure(Data.Builder().putString(KEY_MESSAGE, message).build())
    }

    private fun log(id: String, reason: String) = PcLog.i(TAG, "model $id: $reason")

    companion object {
        const val KEY_MODEL_ID = "model_id"
        /** Progress data: Float 0..1. */
        const val KEY_PROGRESS = "progress"
        /** Failure output: one of the MSG_ strings, safe to show as-is. */
        const val KEY_MESSAGE = "message"
        const val MSG_NOT_VERIFIED = "Download didn't verify \u2014 try again"
        const val MSG_NO_SPACE = "Not enough space"
        const val MSG_FAILED = "Download failed \u2014 try again"

        private const val TAG = "LocalModelDownload"
        private const val SPACE_FACTOR = 1.2
        private const val MAX_ATTEMPTS = 6
        private const val BUFFER = 64 * 1024
        /** Per model file, process-wide (only this process writes filesDir/models). */
        private val writers = ConcurrentHashMap<String, Mutex>()

        fun uniqueName(modelId: String) = "local_model_$modelId"
        fun partFile(target: File) = File(target.parentFile, target.name + ".part")

        /** "bytes 1000-1999/2000" -> 1000; anything else -> null. */
        private fun rangeStart(contentRange: String?): Long? =
            contentRange?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

        @VisibleForTesting internal var resolve: (String) -> LocalModel? = LocalModels::byId
        @VisibleForTesting internal var client: () -> OkHttpClient = { Http.downloadClient() }
        @VisibleForTesting internal var freeBytes: (File) -> Long = { it.usableSpace }

        @VisibleForTesting internal fun resetForTests() {
            resolve = LocalModels::byId
            client = { Http.downloadClient() }
            freeBytes = { it.usableSpace }
        }
    }
}
