package com.rokid.context.memory

import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.SpaceState

/** Feeds the spec §2.2 banner "Index rebuild needed / running". */
enum class IndexHealth {
    NONE, NEEDED, RUNNING;

    companion object {
        fun of(spaces: List<EmbeddingSpaceEntity>): IndexHealth = when {
            spaces.any { it.state == SpaceState.BUILDING } -> RUNNING
            spaces.any { it.state == SpaceState.STALE } -> NEEDED
            else -> NONE
        }
    }
}
