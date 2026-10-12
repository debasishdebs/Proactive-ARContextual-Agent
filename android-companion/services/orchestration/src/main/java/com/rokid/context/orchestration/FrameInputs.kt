package com.rokid.context.orchestration

import android.util.Base64
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.model.ModelImageInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Frame JPEGs → vision inputs (moved out of QueueDrainWorker; the test bed reuses it). Unreadable files are skipped. */
object FrameInputs {
    suspend fun fromFrames(frames: List<FrameEntity>, maxImages: Int): List<ModelImageInput> = withContext(Dispatchers.IO) {
        if (maxImages <= 0) return@withContext emptyList()
        frames.asSequence().mapNotNull { frame ->
            val file = frame.path.takeIf { it.isNotBlank() }?.let(::File) ?: return@mapNotNull null
            if (!file.canRead() || file.length() <= 0L) return@mapNotNull null
            val raw = runCatching { file.readBytes() }.getOrNull() ?: return@mapNotNull null
            val mime = when (file.extension.lowercase(Locale.US)) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            ModelImageInput(mime, Base64.encodeToString(raw, Base64.NO_WRAP), detail = "low")
        }.take(maxImages).toList()
    }
}
