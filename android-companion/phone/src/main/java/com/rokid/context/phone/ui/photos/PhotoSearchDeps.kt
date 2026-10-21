package com.rokid.context.phone.ui.photos

import android.content.Context
import androidx.room.InvalidationTracker
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.recall.DescribeResult
import com.rokid.context.work.frames.recall.ImageSearchState
import com.rokid.context.work.frames.recall.PhotoRecall
import com.rokid.context.work.frames.recall.RecallResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow

/** What the photo search screen needs from the app graphs (wave 3 Task 4): recall, describe on demand, the stored paths and the change signal. */
class PhotoSearchDeps(
    val search: suspend (String) -> RecallResult,
    val reenrich: suspend (RecallResult) -> RecallResult,
    val describePhotos: suspend (List<String>) -> DescribeResult,
    val describeScene: suspend (String) -> DescribeResult,
    val framePaths: suspend (List<String>) -> Map<String, String>,
    /** Room invalidation of frame, frame_meta, memory and frame_source (S11). */
    val changes: Flow<Unit>,
) {
    companion object {
        /** Nothing found, nothing described: the default of [com.rokid.context.phone.ui.shell.PhoneDeps] in tests. */
        val None = PhotoSearchDeps(
            { RecallResult(emptyList(), emptyList(), ImageSearchState.NO_IMAGE_SPACE, 0, false) }, { it }, { DescribeResult.Unavailable }, { DescribeResult.Unavailable },
            { emptyMap() }, emptyFlow(),
        )

        /** The graphs are touched on first use (a search), never when [com.rokid.context.phone.ui.shell.PhoneDeps] is built on the main thread. */
        @OptIn(FlowPreview::class)
        fun create(app: Context): PhotoSearchDeps {
            val recall by lazy { PhotoRecall.forApp(app) }
            val memory by lazy { MemoryGraph.get(app) }
            return PhotoSearchDeps(
                search = { recall.search(it) },
                reenrich = { recall.reenrich(it) },
                describePhotos = { KeyframeGraph.get(app).describe.describePhotos(it) },
                describeScene = { KeyframeGraph.get(app).describe.describeScene(it) },
                framePaths = { ids -> memory.timeline.framesByIds(ids).associate { it.id to it.path } },
                changes = flow { emitAll(invalidations(MemoryGraph.get(app).db, "frame", "frame_meta", "memory", "frame_source")) }.debounce(400).flowOn(Dispatchers.IO), // the graphs open off Main (M5)
            )
        }

        /** Room's own public change signal ([InvalidationTracker.Observer]), no custom bus (spec §8). */
        private fun invalidations(db: ContextDatabase, vararg tables: String): Flow<Unit> = callbackFlow {
            val observer = object : InvalidationTracker.Observer(tables) {
                override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
            }
            db.invalidationTracker.addObserver(observer)
            awaitClose { db.invalidationTracker.removeObserver(observer) }
        }
    }
}
