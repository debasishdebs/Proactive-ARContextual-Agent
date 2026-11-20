package com.rokid.context.phone.ui.photos

import android.content.Context
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.list.tableChanges
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.recall.DescribeResult
import com.rokid.context.work.frames.recall.ImageSearchState
import com.rokid.context.work.frames.recall.PhotoRecall
import com.rokid.context.work.frames.recall.RecallResult
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Slice 2: the data saver would hold a photo upload right now (the "Send over mobile data once" dialog asks this). */
    val photoHeld: suspend () -> Boolean = { false },
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
                photoHeld = { withContext(Dispatchers.IO) { KeyframeGraph.get(app).photoHeldForPrompt() } },
                changes = flow { emitAll(MemoryGraph.get(app).db.tableChanges("frame", "frame_meta", "memory", "frame_source")) }.debounce(400).flowOn(Dispatchers.IO), // the graphs open off Main (M5)
            )
        }
    }
}
