package com.rokid.context.work.frames.intent

import com.rokid.context.canvas.FrameIntentSink
import com.rokid.context.core.model.context.IntentLabel

/**
 * Writes a Live canvas publish's intent label to `frame_meta` through the same store and fences as [IntentSampler]: the label must be a known
 * [IntentLabel]; no write for a forgotten (tombstoned) frame; the update is checked by its affected-row count (0 = the frame is gone, nothing
 * stored); a wipe ([generation]) between entry and the write drops it (a frame_meta row removed by Forget or Delete all is never recreated: the update is an UPDATE). The canvas sink call carries no generation, so this writer takes its own at entry.
 * Never throws except cancellation.
 */
class FrameIntentWriter(
    private val store: IntentFrameStore,
    private val generation: () -> Long = { 0L },
) : FrameIntentSink {
    /** Rows stored by the last record (tests). */
    @Volatile var lastRows: Int = -1
        private set

    override suspend fun record(frameId: String, label: String, confidence: Float?, atMs: Long) {
        lastRows = -1
        val known = IntentLabel.entries.firstOrNull { it.name == label } ?: return
        val gen = generation()
        try {
            if (store.tombstoned(frameId)) return
            if (generation() != gen) return
            val conf = (confidence ?: 0f).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
            lastRows = store.setIntent(frameId, known.name, conf, atMs)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            runCatching { android.util.Log.w("FrameIntentWriter", "intent write failed: ${e.javaClass.simpleName}") }
        }
    }
}
