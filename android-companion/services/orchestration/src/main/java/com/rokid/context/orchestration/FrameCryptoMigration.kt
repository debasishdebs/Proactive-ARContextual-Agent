package com.rokid.context.orchestration

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.frames.FrameImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gaps G1: brings every frame JPEG in [RokidFrameInbox.FRAME_DIRS] (glasses frames and phone captures) to the current
 * "Encrypt memory" choice, one file at a time. Each file is its own atomic step ([FrameImages.convert]: copy, fsync,
 * swap), so a run stopped or killed anywhere resumes from what is left and never loses a file. The swap runs under
 * [WipeFence.mutex] and only if no Delete all started since the pass began, so a wipe is never undone.
 */
object FrameCryptoMigration {
    private const val TAG = "FrameCryptoMigration"
    private val frameExt = setOf("jpg", "jpeg", "png", "webp")

    /** DONE: every file is in the wanted form. STOPPED: asked to stop. CHANGED: the choice flipped mid-pass. FAILED: some file failed. */
    enum class Outcome { DONE, STOPPED, CHANGED, FAILED }

    suspend fun run(
        context: Context,
        isStopped: () -> Boolean = { false },
        onConverted: () -> Unit = {},
        beforeCommit: () -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val toSealed = FrameImages.encryptionWanted()
        val generation = MemoryWipe.generation.get()
        val dirs = RokidFrameInbox.FRAME_DIRS.map { File(context.filesDir, it) }
        dirs.forEach { FrameImages.sweepTemps(it) }
        var failed = 0
        for (dir in dirs) {
            val files = dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase(Locale.US) in frameExt }.sortedBy { it.name }
            for (file in files) {
                if (isStopped()) return@withContext Outcome.STOPPED
                if (FrameImages.encryptionWanted() != toSealed) return@withContext Outcome.CHANGED
                val r = FrameImages.convert(
                    file, toSealed,
                    commit = { swap -> WipeFence.mutex.withLock { MemoryWipe.generation.get() == generation && swap() } },
                    beforeCommit = beforeCommit,
                )
                when (r) {
                    FrameImages.Convert.DONE -> onConverted()
                    FrameImages.Convert.FAILED -> failed++
                    FrameImages.Convert.SKIPPED -> Unit
                }
            }
        }
        if (failed > 0) Log.w(TAG, "frames not converted=$failed; retrying later")
        if (failed > 0) Outcome.FAILED else Outcome.DONE
    }
}

/** Runs [FrameCryptoMigration] in the background: on a toggle and at app start (a no-op when nothing needs converting). */
class FrameCryptoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        when (FrameCryptoMigration.run(applicationContext, isStopped = { isStopped })) {
            FrameCryptoMigration.Outcome.DONE -> Result.success()
            FrameCryptoMigration.Outcome.CHANGED -> { enqueueAgain(applicationContext); Result.success() }
            FrameCryptoMigration.Outcome.STOPPED, FrameCryptoMigration.Outcome.FAILED -> Result.retry()
        }
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        FrameImages.fail(FrameImages.Code.SWAP, e)
        Result.retry()
    }

    companion object {
        const val NAME = "frame_crypto"

        private fun request() = OneTimeWorkRequestBuilder<FrameCryptoWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        /** The toggle and app start. REPLACE: a pass toward the old choice stops between two files (each one is whole). */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request())
        }

        private fun enqueueAgain(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
        }
    }
}
