package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority

class FrameIngestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sourceImagePath = inputData.getString(KEY_SOURCE_IMAGE_PATH).orEmpty()
        if (sourceImagePath.isNotBlank()) {
            RokidFrameInbox.ingestSinglePath(applicationContext, sourceImagePath, source = "rokid_single_push")
        }

        val imported = RokidFrameInbox.ingestPending(applicationContext, maxItems = 16)
        if (imported > 0) {
            val config = AppConfigStore.getWithoutSecret(applicationContext)
            QueueRuntimeStore.enqueue(
                context = applicationContext,
                queue = QueuePriority.SUMMARIZER,
                payload = QueuePayloads.batchedWrite(source = "frame_ingest_worker", importedFrames = imported, manual = false),
                personaSnapshot = config.persona,
                ttlHours = 24,
            )
            OrchestrationScheduler(applicationContext).enqueueQueueDrainNow()
        }

        return Result.success()
    }

    companion object {
        const val KEY_SOURCE_IMAGE_PATH = "source_image_path"
    }
}
