package com.rokid.context.work.frames.scene

import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Modality
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import kotlinx.coroutines.CancellationException
import com.rokid.context.orchestration.IndexConfigException

/**
 * A space that can hold image vectors: the ACTIVE one, plus the BUILDING one during a re-index (both are written; only the
 * [active] one is read for routing). [imageDims] is the stored image vector width, as the embedder declares it.
 */
class ImageSpaceTarget(
    val spaceId: String,
    val fingerprint: String,
    val imageDims: Int,
    val embedImages: suspend (List<ByteArray>) -> List<FloatArray>,
    val embedQueryFull: suspend (String) -> FloatArray,
    val active: Boolean = true,
    /** Several query texts in one call (Place Guide shadow scoring); defaults to one [embedQueryFull] per text. */
    val embedQueriesFull: suspend (List<String>) -> List<FloatArray> = { ts -> ts.map { embedQueryFull(it) } },
)

/** The spaces to write image vectors into, [active?, building?]; only spaces whose embedder has [Modality.IMAGE]. */
fun interface ImageSpaces {
    suspend fun targets(): List<ImageSpaceTarget>
}

/** Real one: [EmbeddingSpaces.queryable]/[EmbeddingSpaces.building] with [SpaceEmbedderProvider.forSpace]; the width is probed once per space. */
class SpaceImageSpaces(private val spaces: EmbeddingSpaces, private val provider: SpaceEmbedderProvider) : ImageSpaces {
    override suspend fun targets(): List<ImageSpaceTarget> =
        listOfNotNull(target(spaces.queryable(), active = true), target(spaces.building(), active = false))

    private suspend fun target(space: EmbeddingSpaceEntity?, active: Boolean): ImageSpaceTarget? {
        space ?: return null
        val e = try { provider.forSpace(space) } catch (c: CancellationException) { throw c } catch (_: Exception) { null } ?: return null
        if (Modality.IMAGE !in e.modalities) return null
        // One width source: what the embedder declares. No probe and no text-width fallback (the text dim can be shorter than the image width).
        val d = e.imageDims ?: throw IndexConfigException("image embedder must declare imageDims")
        return ImageSpaceTarget(space.id, space.fingerprint, d, e::embedImages, e::embedQueryFull, active, e::embedQueriesFull)
    }
}
