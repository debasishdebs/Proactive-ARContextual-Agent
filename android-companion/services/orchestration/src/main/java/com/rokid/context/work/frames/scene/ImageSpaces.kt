package com.rokid.context.work.frames.scene

import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.Modality
import com.rokid.context.memory.SpaceEmbedderProvider
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * A space that can hold image vectors: the ACTIVE one, plus the BUILDING one during a re-index (both are written; only the
 * [active] one is read for routing). [imageDims] is the stored image vector width.
 */
class ImageSpaceTarget(
    val spaceId: String,
    val fingerprint: String,
    val imageDims: Int,
    val embedImages: suspend (List<ByteArray>) -> List<FloatArray>,
    val embedQueryFull: suspend (String) -> FloatArray,
    val active: Boolean = true,
)

/** The spaces to write image vectors into, [active?, building?]; only spaces whose embedder has [Modality.IMAGE]. */
fun interface ImageSpaces {
    suspend fun targets(): List<ImageSpaceTarget>
}

/** Real one: [EmbeddingSpaces.queryable]/[EmbeddingSpaces.building] with [SpaceEmbedderProvider.forSpace]; the width is probed once per space. */
class SpaceImageSpaces(private val spaces: EmbeddingSpaces, private val provider: SpaceEmbedderProvider) : ImageSpaces {
    private val dims = ConcurrentHashMap<String, Int>()

    override suspend fun targets(): List<ImageSpaceTarget> =
        listOfNotNull(target(spaces.queryable(), active = true), target(spaces.building(), active = false))

    private suspend fun target(space: EmbeddingSpaceEntity?, active: Boolean): ImageSpaceTarget? {
        space ?: return null
        val e = try { provider.forSpace(space) } catch (c: CancellationException) { throw c } catch (_: Exception) { null } ?: return null
        if (Modality.IMAGE !in e.modalities) return null
        val d = dims[space.id] ?: probe(e, space).also { dims[space.id] = it }
        return ImageSpaceTarget(space.id, space.fingerprint, d, e::embedImages, e::embedQueryFull, active)
    }

    private suspend fun probe(e: com.rokid.context.memory.Embedder, space: EmbeddingSpaceEntity): Int =
        try { e.embedQueryFull("probe").size } catch (c: CancellationException) { throw c } catch (_: Exception) { space.dim }
}
