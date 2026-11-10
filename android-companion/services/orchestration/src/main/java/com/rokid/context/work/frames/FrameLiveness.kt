package com.rokid.context.work.frames

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.model.GonePhotos
import com.rokid.context.model.PhotoLiveness
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe

/**
 * Codex r10: the ONE production [PhotoLiveness]. Callers only build it and attach it (with their image refs) to the model call; the ordering lives in
 * `ModelRouter.call` (permit, then [holdUploadSlot], then [gone], then the upload). The slot is the [DataOpGate]'s dispatch side, so a Forget, Delete all
 * or retention delete either finished before the re-check (which then sees the photo gone and drops the call) or waits for the upload to end.
 * A photo is live while its `frame` row exists, it is not tombstoned (forgotten), and Delete all has not run since [generation].
 */
class FrameLiveness(
    private val generation: Long,
    private val live: suspend (Set<String>) -> Set<String>,
    /** Codex r12: of the source memory ids, those forgotten ([com.rokid.context.memory.forget.ForgetFence.goneAmong]). */
    private val goneMemoryIds: suspend (Set<String>) -> Set<String> = { emptySet() },
    /** Codex r15: of the mark label ids, those whose `canvas_label` row is gone. */
    private val goneMarkIds: suspend (Set<String>) -> Set<String> = { emptySet() },
    private val gate: () -> DataOpGate = { DataOpGate.shared },
    private val currentGeneration: () -> Long = { MemoryWipe.generation.get() },
) : PhotoLiveness {
    override suspend fun <T> holdUploadSlot(block: suspend () -> T): T = gate().dispatch(block)

    override suspend fun goneMemories(ids: Set<String>): Set<String> = if (currentGeneration() != generation) ids else goneMemoryIds(ids)

    override suspend fun goneMarks(ids: Set<String>): Set<String> = if (currentGeneration() != generation) ids else goneMarkIds(ids)

    override suspend fun gone(ids: Set<String>): GonePhotos =
        if (currentGeneration() != generation) GonePhotos(ids, wiped = true) else GonePhotos(ids - live(ids))

    companion object {
        private const val MAX_IN = 500

        /** Rows present and not tombstoned, read from [g]. */
        fun forGraph(g: MemoryGraph, generation: Long = MemoryWipe.generation.get()) = FrameLiveness(generation, { ids ->
            val list = ids.toList()
            val present = list.chunked(MAX_IN).flatMap { g.frames.byIds(it) }.map { it.id }.toSet()
            val locked = list.chunked(MAX_IN).flatMap { g.db.forgetDao().tombstoned(it) }.toSet()
            present - locked
        }, { ids -> com.rokid.context.memory.forget.ForgetFence.goneAmong(g.db, ids) },
            { ids -> ids.filter { g.db.canvasSlice3Dao().label(it) == null }.toSet() })

        fun forApp(app: Context, generation: Long = MemoryWipe.generation.get()) = forGraph(MemoryGraph.get(app), generation)
    }
}
