package com.rokid.context.work.frames

import android.content.Context
import com.rokid.context.orchestration.ExtractPhoto
import com.rokid.context.orchestration.FrameInputs
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photos one extraction call sends, and why the others are missing. [gone] frames can never be read again (the row or the
 * file is gone, or the frame is forgotten or tombstoned): FAILED_FINAL at once. [unseen] frames have a file that could not be
 * opened just now (Keystore unseal failed, a crypto conversion is running): a normal failure with an attempt, retried after its
 * backoff (review I3).
 */
class FramePhotos(val photos: List<ExtractPhoto>, val gone: Set<String>, val unseen: Set<String>) {
    companion object {
        suspend fun load(app: Context, frameIds: List<String>): FramePhotos {
            // Forgotten (tombstoned), deleted and missing-file frames drop out of framesByIds.
            val byId = PipelineRecords.framesByIds(app, frameIds).associateBy { it.id }
            val photos = mutableListOf<ExtractPhoto>()
            val gone = mutableSetOf<String>()
            val unseen = mutableSetOf<String>()
            for (id in frameIds) {
                val f = byId[id]
                val size = f?.let { fr -> withContext(Dispatchers.IO) { File(fr.path).takeIf { it.isFile }?.length() } } ?: 0L
                if (f == null || size <= 0L) { gone += id; continue }
                val image = FrameInputs.fromFrames(listOf(f), maxImages = 1).singleOrNull()
                if (image == null) unseen += id else photos += ExtractPhoto(f.id, "ts=${f.ts} \u00b7 source=${f.source} \u00b7 ${f.payload}", image)
            }
            return FramePhotos(photos, gone, unseen)
        }
    }
}
