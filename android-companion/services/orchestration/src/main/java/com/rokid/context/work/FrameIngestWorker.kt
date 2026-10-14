package com.rokid.context.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rokid.context.connectors.sources.SourcesGraph
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
        // R-64: one place lookup per run, and only when Location is on and granted (else null). Never sent to the glasses.
        val place = SourcesGraph.get(applicationContext).placeLabel(System.currentTimeMillis())
        val sourceImagePath = inputData.getString(KEY_SOURCE_IMAGE_PATH).orEmpty()
        val ids = mutableListOf<String>()
        if (sourceImagePath.isNotBlank()) {
            val source = inputData.getString(KEY_SOURCE)?.takeIf { it.startsWith("rokid") } ?: "rokid_single_push"
            RokidFrameInbox.ingestSinglePath(applicationContext, sourceImagePath, source = source, placeLabel = place)?.let(ids::add)
        }
        ids += RokidFrameInbox.ingestPendingIds(applicationContext, maxItems = 16, placeLabel = place)

        // Periodic backstop (spec P2.5): abandoned claims go back to PENDING and the pipeline is woken for anything claimable.
        val keyframes = com.rokid.context.work.frames.KeyframeGraph.get(applicationContext)
        runCatching { keyframes.pipeline.recover() }
        keyframes.signal()

        val imported = ids.size
        if (imported > 0) {
            val config = AppConfigStore.getWithoutSecret(applicationContext)
            QueueRuntimeStore.enqueue(
                context = applicationContext,
                queue = QueuePriority.SUMMARIZER,
                // Exactly this run's frames: a phone capture never re-summarises older frames (fix round 1).
                payload = QueuePayloads.batchedWrite(source = "frame_ingest_worker", importedFrames = imported, manual = false, frameIdsCsv = ids.joinToString(",")),
                personaSnapshot = config.persona,
                ttlHours = 24,
            )
            OrchestrationScheduler(applicationContext).enqueueQueueDrainNow()
        }

        return Result.success()
    }

    companion object {
        const val KEY_SOURCE_IMAGE_PATH = "source_image_path"
        /** The frame source for [KEY_SOURCE_IMAGE_PATH] (phone "Capture now": "rokid_phone"); it must keep the rokid prefix. */
        const val KEY_SOURCE = "source"
    }
}
