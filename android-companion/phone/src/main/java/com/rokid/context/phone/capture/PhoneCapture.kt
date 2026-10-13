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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Settings › Phone camera & mic "Capture now" (R-66) as the screen sees it. */
interface CaptureNowControl {
    /** Capture is paused (any active pause): Capture now is disabled. */
    val paused: StateFlow<Boolean>
    /** A fresh FileProvider target for TakePicture, or null if none could be made. Off Main. */
    suspend fun prepare(): Uri?
    /** TakePicture returned: [saved] = false (cancelled) writes nothing. True when the photo entered the pipeline. */
    suspend fun finish(saved: Boolean): Boolean

    companion object {
        val None: CaptureNowControl = object : CaptureNowControl {
            override val paused: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
            override suspend fun prepare(): Uri? = null
            override suspend fun finish(saved: Boolean) = false
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

/** The production [CaptureNowControl]: one capture at a time; the target is kept until TakePicture returns. */
class PhoneCaptureControl(
    private val app: Context,
    override val paused: StateFlow<Boolean>,
    private val capture: PhoneCapture = PhoneCapture(app),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CaptureNowControl {
    @Volatile private var pending: File? = null

    override suspend fun prepare(): Uri? = withContext(io) {
        runCatching { capture.target().also { pending = it }.let { PhoneCapture.uriFor(app, it) } }.getOrNull()
    }

    override suspend fun finish(saved: Boolean): Boolean {
        val file = pending ?: return false
        pending = null
        if (!saved) { withContext(io) { capture.discard(file) }; return false }
        return capture.ingest(file)
    }
}
