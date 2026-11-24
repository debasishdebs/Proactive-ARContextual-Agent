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
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
import com.rokid.context.data.models.LocalModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
        /** The data saver closed (roaming, unknown network, daily limit, hotspot, Offline only): the .part stays and the work waits for Wi-Fi. */
        data object Closed : Outcome
        data class Fail(val message: String, val reason: String) : Outcome
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_MODEL_ID).orEmpty()
        val model = resolve(id) ?: return fail(id, MSG_FAILED, "unknown_model")
        if (model.files.any { !it.url.startsWith("https://") }) return fail(id, MSG_FAILED, "not_https")
        // "I'm abroad" was switched on after this download was queued with "Allow mobile data": a local foreign SIM does not report roaming, so no byte goes over a metered network until it is off (Wi-Fi still runs).
        // Not a retry (that would keep the mobile-data request, back off for hours and use up attempts): the work is replaced by a Wi-Fi-only one, which WorkManager starts when Wi-Fi is there. The .part is kept.
        if (LocalModelStore.abroad(applicationContext) && meteredNow()) { LocalModelStore(applicationContext).waitForWifi(model); return Result.success().also { modelEvent(id, "abroad_metered_wait_wifi") } }
        // The shared data saver admission (BULK_OTHER: daily caps, hotspot / metered-Wi-Fi override, ledger) is asked before the first byte and again while bytes flow. Fail closed: roaming or an unknown network never downloads.
        if (!admitted()) return waitForWifiNow(model)
        val outcome = try {
            // One writer per model file: a replacement waits until the stopped worker has let go of the .part.
            withContext(Dispatchers.IO) { downloadAll(model) }
        } catch (e: IOException) {
            Outcome.Retry.also { modelEvent(id, "io") }
        }
        return when (outcome) {
            Outcome.Done -> Result.success().also { modelEvent(id, "done") }
            Outcome.Closed -> waitForWifiNow(model)
            Outcome.Retry -> if (runAttemptCount + 1 >= MAX_ATTEMPTS) fail(id, MSG_FAILED, "gave_up") else Result.retry()
            is Outcome.Fail -> fail(id, outcome.message, outcome.reason)
        }
    }

    private val gate: DataGate by lazy { gateOf(applicationContext) }

    /** One admission check of the shared gate for a bulk model download (a remote host). A refusal is counted once in the "other big jobs" ledger. */
    private fun admitted(): Boolean {
        val d = try { gate.decide(DataKind.BULK_OTHER, EndpointReach.REMOTE) } catch (e: Exception) { return false } // an unreadable gate is closed
        if (!d.allowed) { gate.skipped(DataKind.BULK_OTHER, Skipped.BULK, d); return false }
        return true
    }

    /**
     * The policy is closed: replace the work with a Wi-Fi-only one (a fresh request, the .part is kept). A closed answer on a network WorkManager itself calls unmetered (a Wi-Fi blip before validation) would replace the work
     * in a loop, so a second closure within [REPLACE_GAP_MS] is a plain retry (the work's own backoff) instead.
     */
    private fun waitForWifiNow(model: LocalModel): Result {
        val now = System.currentTimeMillis()
        val last = lastWait.put(model.id, now)
        modelEvent(model.id, "data_saver_closed")
        if (last != null && now - last < REPLACE_GAP_MS) return Result.retry()
        LocalModelStore(applicationContext).waitForWifi(model)
        return Result.success()
    }

    private fun meteredNow(): Boolean = try { applicationContext.getSystemService(android.net.ConnectivityManager::class.java)?.isActiveNetworkMetered != false } catch (e: Exception) { true }

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
            if (!admitted()) return Outcome.Closed
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

    /** Set when the policy watcher below closed the running transfer (reset for each file). */
    private val policyClosed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Cancels [call] the moment this worker is stopped, so a read blocked on a slow link ends at once. Codex data saver r2 P1-7: it also re-asks the shared policy every [policyWatchMs] (quietly: no
     * count) and cancels the call when it closes (hotspot metering switched on, roaming, the daily limit), because a read blocked on a slow link would otherwise never reach the per-chunk check.
     */
    private suspend fun <T> cancelOnStop(call: Call, block: suspend () -> T): T = coroutineScope {
        policyClosed.set(false)
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        val policy = launch {
            while (true) {
                delay(policyWatchMs)
                val open = try { gate.decide(DataKind.BULK_OTHER, EndpointReach.REMOTE).allowed } catch (e: Exception) { false }
                if (!open) { policyClosed.set(true); call.cancel(); return@launch }
            }
        }
        try { block() } finally { watcher.cancel(); policy.cancel() }
    }

    /** Appends the body to [part]; null when it ended and the .part is ready to verify, else the outcome. */
    private suspend fun transfer(call: Call, id: String, model: ModelFile, part: File, from: Long, before: Long, total: Long): Outcome? {
        var have = from
        // Bytes read and not yet reported to the data saver ledger: reported every [REPORT_BYTES] and, from the finally block, whatever is left (a cancelled, failed or closed transfer is counted too).
        var unreported = 0L
        // Codex r2 P1-7: the cost class is NOT latched for the transfer. Every interval (a read, reported every [REPORT_BYTES]) is classified by the CURRENT network: an interval is counted when the network was costly at any read in it.
        fun costlyNow(): Boolean = try { gate.costlyNow() } catch (e: Exception) { true }
        var intervalCostly = costlyNow()
        try {
        call.execute().use { response ->
            if (!response.request.url.isHttps) return Outcome.Fail(MSG_FAILED, "not_https")
            val append = when (response.code) {
                206 -> {
                    if (rangeStart(response.header("Content-Range")) != have) { part.delete(); return Outcome.Retry }
                    true
                }
                200 -> false.also { have = 0 }
                416 -> null
                408, 429, in 500..599 -> return Outcome.Retry.also { modelEvent(id, "http_${response.code}") }
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
                            if (!intervalCostly) intervalCostly = costlyNow()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (!intervalCostly) intervalCostly = costlyNow() // the class right after the bytes arrived
                            if (stopped()) return Outcome.Retry // stopped while the read was blocked: drop the chunk
                            if (have + n > model.bytes) { part.delete(); return Outcome.Fail(MSG_NOT_VERIFIED, "too_long") }
                            out.write(buf, 0, n)
                            have += n
                            unreported += n
                            if (unreported >= REPORT_BYTES) {
                                val bytes = unreported; unreported = 0
                                gate.sentAt(DataKind.BULK_OTHER, 0L, bytes, 0, intervalCostly)
                                intervalCostly = costlyNow()
                                if (!admitted()) return Outcome.Closed // the policy closed mid-transfer (daily limit, roaming, hotspot): stop now, the .part stays
                            }
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
        } catch (e: IOException) {
            if (policyClosed.get()) { admitted(); return Outcome.Closed } // the policy watcher cancelled the socket: counted once as a refusal, the .part stays
            throw e
        } finally {
            val bytes = unreported
            if (bytes > 0) try { gate.sentAt(DataKind.BULK_OTHER, 0L, bytes, 0, intervalCostly || costlyNow()) } catch (e: Exception) { }
        }
        return null
    }

    private fun fail(id: String, message: String, reason: String): Result {
        modelEvent(id, reason)
        return Result.failure(Data.Builder().putString(KEY_MESSAGE, message).build())
    }

    private fun modelEvent(catalogId: String, reason: String) = PcLog.i(TAG, "model $catalogId: $reason")

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
        /** Bytes read between two reports to the data saver (and two policy re-checks). */
        private const val REPORT_BYTES = 256 * 1024L
        private const val REPLACE_GAP_MS = 60_000L
        /** Last time a closed policy replaced a model's work (process-wide). */
        private val lastWait = ConcurrentHashMap<String, Long>()
        /** Per model file, process-wide (only this process writes filesDir/models). */
        private val writers = ConcurrentHashMap<String, Mutex>()

        fun uniqueName(modelId: String) = "local_model_$modelId"
        fun partFile(target: File) = File(target.parentFile, target.name + ".part")

        /** "bytes 1000-1999/2000" -> 1000; anything else -> null. */
        private fun rangeStart(contentRange: String?): Long? =
            contentRange?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

        @VisibleForTesting internal var resolve: (String) -> LocalModel? = LocalModels::byId
        @VisibleForTesting internal var client: () -> OkHttpClient = { Http.downloadClient() }
        /** How often a running download re-asks the shared policy (tests shorten it). */
        @VisibleForTesting internal var policyWatchMs: Long = 1_000L
        @VisibleForTesting internal var freeBytes: (File) -> Long = { it.usableSpace }

        @VisibleForTesting internal var gateOf: (Context) -> DataGate = { com.rokid.context.ai.datasaver.DataSaverGraph.get(it) }

        @VisibleForTesting internal fun resetForTests() {
            gateOf = { com.rokid.context.ai.datasaver.DataSaverGraph.get(it) }
            lastWait.clear()
            resolve = LocalModels::byId
            client = { Http.downloadClient() }
            freeBytes = { it.usableSpace }
            policyWatchMs = 1_000L
        }
    }
}
