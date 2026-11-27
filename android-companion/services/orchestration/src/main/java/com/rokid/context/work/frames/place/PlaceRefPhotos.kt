package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.memory.ImageTextEmbedder
import com.rokid.context.memory.place.PlacePack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Why one run of [PlacePhotoEmbedder] ended. Only the last two are final for now; the others are waits (the work is resumable: the rows are the state). */
enum class EmbedEnd { DONE, OFF, HOT, NO_SPACE }

/**
 * B2 (design 4.3, owner D4): embeds the stored thumbnails of seeded places with the SAME image embedder and vector space the Moments use (the ACTIVE space), one image at a time, in batches of [batch] with a short pause.
 * Gates, asked before EVERY image: the Developer switch [enabled] and [coolEnough] (thermal below MODERATE). No charging rule: it runs right after the photos are downloaded and resumes later when the phone has cooled down.
 * Resumable: the queue is [PlacePack.needsEmbed] (a thumbnail without a vector of the active space), so a vector-space change re-embeds from the STORED thumbnail, and a place whose vector is not ready simply has no WEB reference (the matcher copes).
 * Nothing here is logged except counts (see the worker); the picture and the vector never leave the phone.
 */
class PlacePhotoEmbedder(
    private val pack: PlacePack,
    private val embedder: suspend () -> ImageTextEmbedder?,
    private val coolEnough: () -> Boolean,
    private val enabled: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val batch: Int = BATCH,
    private val batchPauseMs: Long = BATCH_PAUSE_MS,
) {
    var embedded = 0; private set
    var failed = 0; private set

    suspend fun run(): EmbedEnd {
        val e = embedder() ?: return EmbedEnd.NO_SPACE
        val bad = HashSet<String>()
        var inBatch = 0
        while (true) {
            gate()?.let { return it }
            val ids = pack.needsEmbed(e.spaceId, batch + bad.size).filter { it !in bad }.take(1)
            if (ids.isEmpty()) return EmbedEnd.DONE
            val row = pack.imagesOf(ids).firstOrNull()
            val jpeg = row?.jpeg
            if (jpeg == null) { bad += ids[0]; continue }
            try {
                val v = e.embedImage(jpeg)
                if (v.isEmpty() || v.size != e.dim || v.any { it.isNaN() }) { bad += ids[0]; failed++ }
                else if (pack.markEmbedded(ids[0], e.spaceId, v, VEC_VERSION, clock())) embedded++ else bad += ids[0]
            } catch (c: CancellationException) { throw c
            } catch (x: Exception) { bad += ids[0]; failed++ }
            if (++inBatch >= batch) { inBatch = 0; pause(batchPauseMs) }
        }
    }

    private fun gate(): EmbedEnd? = when {
        !enabled() -> EmbedEnd.OFF
        !coolEnough() -> EmbedEnd.HOT
        else -> null
    }

    companion object {
        const val BATCH = 25
        const val BATCH_PAUSE_MS = 2_000L
        /** The stored `vecVersion` of a place photo vector. */
        const val VEC_VERSION = 1
    }
}

/** The glue the workers use: the active image space's name and embedder, and the two phone facts. */
object PlaceRefPhotos {
    private fun spaces(app: Context) = com.rokid.context.work.frames.scene.SpaceImageSpaces(com.rokid.context.memory.MemoryGraph.get(app).spaces, com.rokid.context.ai.AiGraph.get(app).embedders)

    /** The ACTIVE image space's id as the matcher reads it (the fingerprint), or null while none is ready. */
    suspend fun activeSpace(app: Context): String? = runCatching { spaces(app).targets().firstOrNull { it.active }?.fingerprint }.getOrNull()

    suspend fun embedder(app: Context): ImageTextEmbedder? = runCatching { spaces(app).targets().firstOrNull { it.active }?.let { ImageSpaceTargetImageText(it) } }.getOrNull()

    /** Thermal status below MODERATE (the Moments' own rule for image vectors, [com.rokid.context.work.budget.BackgroundWork.IMAGE_VECTORS]). */
    fun coolEnough(app: Context): Boolean = runCatching { com.rokid.context.work.frames.KeyframeGraph.get(app).power.mayRunBackground(com.rokid.context.work.budget.BackgroundWork.IMAGE_VECTORS) }.getOrDefault(false)

    fun embedder(app: Context, pack: PlacePack) = PlacePhotoEmbedder(
        pack, { embedder(app) }, { coolEnough(app) }, { com.rokid.context.orchestration.forget.DeveloperFlags.placeRefPhotos(app) },
    )
}
