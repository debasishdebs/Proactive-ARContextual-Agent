package com.rokid.context.phone.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.work.FrameIngestWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed interface CapturePrepare {
    data class Ready(val uri: Uri) : CapturePrepare
    data object Paused : CapturePrepare
    data object Failed : CapturePrepare
}

enum class CaptureResult { SAVED, CANCELLED, PAUSED, FAILED }

/** Settings › Phone camera & mic "Capture now" (R-66) as the screen sees it. */
interface CaptureNowControl {
    /** Capture is paused (any active pause), as last read on IO by [refresh]: Capture now is disabled. */
    val paused: StateFlow<Boolean>
    /** Re-reads the pause set on IO (screen open and resume). */
    fun refresh()
    /** Re-checks the pause set, deletes stale shots, then makes a FileProvider target for TakePicture. Off Main. */
    suspend fun prepare(): CapturePrepare
    /** TakePicture returned. Cancelled, or paused meanwhile: the file is deleted and nothing is ingested. */
    suspend fun finish(saved: Boolean): CaptureResult

    companion object {
        val None: CaptureNowControl = object : CaptureNowControl {
            override val paused: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
            override fun refresh() = Unit
            override suspend fun prepare(): CapturePrepare = CapturePrepare.Failed
            override suspend fun finish(saved: Boolean) = CaptureResult.FAILED
        }
    }
}

/**
 * R-66: a phone photo goes into the same pipeline as a glasses frame. The camera writes full size into
 * `cache/capture/phone_<ts>.jpg`; [ingest] scales it to ≤ 360 px wide at JPEG q62 (the glasses guardrails), writes it
 * into the frame archive as `rokid_phone_<ts>.jpg` and enqueues the frame ingest with source `rokid_phone` (the
 * pipeline only keeps `rokid`-prefixed sources).
 */
class PhoneCapture(
    private val app: Context,
    private val enqueue: (File, String) -> Unit = { f, s -> enqueueIngest(app, f, s) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun target(): File = File(File(app.cacheDir, DIR).apply { mkdirs() }, "phone_${clock()}.jpg")

    /** Shots in `cache/capture`, newest first. */
    fun pending(): List<File> = File(app.cacheDir, DIR).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun discard(file: File) { runCatching { file.delete() } }

    suspend fun ingest(file: File): Boolean = withContext(io) {
        try {
            if (!file.isFile || file.length() == 0L) return@withContext false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext false
            val scaled = if (decoded.width <= MAX_WIDTH) decoded else
                Bitmap.createScaledBitmap(decoded, MAX_WIDTH, (decoded.height.toLong() * MAX_WIDTH / decoded.width).toInt().coerceAtLeast(1), true)
            val out = File(RokidFrameInbox.archiveDir(app).apply { mkdirs() }, "${SOURCE}_${clock()}.jpg")
            val ok = out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            if (!ok) { out.delete(); return@withContext false }
            enqueue(out, SOURCE)
            true
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.i(TAG, "capture ingest failed (${e.javaClass.simpleName})")
            false
        } finally {
            discard(file)
        }
    }

    companion object {
        private const val TAG = "PhoneCapture"
        const val DIR = "capture"
        const val SOURCE = "rokid_phone"
        const val MAX_WIDTH = 360
        const val QUALITY = 62

        fun enqueueIngest(app: Context, file: File, source: String) {
            val req = OneTimeWorkRequestBuilder<FrameIngestWorker>()
                .setInputData(workDataOf(FrameIngestWorker.KEY_SOURCE_IMAGE_PATH to file.absolutePath, FrameIngestWorker.KEY_SOURCE to source)).build()
            WorkManager.getInstance(app).enqueue(req)
        }

        fun uriFor(app: Context, file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }
}

/**
 * The production [CaptureNowControl]. [pausedNow] reads the stored pause set on IO (fail-closed: paused until the first
 * read). The target is not kept in memory: after process death [finish] takes the newest file in `cache/capture`,
 * and [prepare] deletes any older shot first (one capture at a time).
 */
class PhoneCaptureControl(
    private val app: Context,
    private val pausedNow: suspend () -> Boolean,
    private val capture: PhoneCapture = PhoneCapture(app),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val uri: (File) -> Uri = { PhoneCapture.uriFor(app, it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CaptureNowControl {
    private val state = MutableStateFlow(true)
    override val paused: StateFlow<Boolean> = state.asStateFlow()

    override fun refresh() { scope.launch { read() } }

    private suspend fun read(): Boolean = withContext(io) {
        try { pausedNow() } catch (c: CancellationException) { throw c } catch (e: Exception) { true } // fail-closed
    }.also { state.value = it }

    override suspend fun prepare(): CapturePrepare {
        if (read()) return CapturePrepare.Paused
        return withContext(io) {
            runCatching {
                capture.pending().forEach(capture::discard)
                CapturePrepare.Ready(uri(capture.target()))
            }.getOrDefault(CapturePrepare.Failed)
        }
    }

    override suspend fun finish(saved: Boolean): CaptureResult {
        val file = withContext(io) { capture.pending().firstOrNull() } ?: return if (saved) CaptureResult.FAILED else CaptureResult.CANCELLED
        if (!saved) { withContext(io) { capture.discard(file) }; return CaptureResult.CANCELLED }
        if (read()) { withContext(io) { capture.discard(file) }; return CaptureResult.PAUSED }
        return if (capture.ingest(file)) CaptureResult.SAVED else CaptureResult.FAILED
    }

    companion object {
        /**
         * Read-only ([com.rokid.context.phone.hub.HubPauses.pausedNow]): a stored record active now, or now inside a
         * quiet-hours occurrence ([com.rokid.context.core.model.QuietWindow]) that was not resumed. Never writes or mints a record.
         */
        fun storedPauses(
            dao: () -> com.rokid.context.memory.db.PauseDao,
            quietHours: () -> com.rokid.context.core.model.QuietHours?,
            zone: () -> java.time.ZoneId = java.time.ZoneId::systemDefault,
            now: () -> Long = System::currentTimeMillis,
        ): suspend () -> Boolean = {
            com.rokid.context.phone.hub.HubPauses(dao(), quietHours, zone, now).pausedNow()
        }
    }
}
