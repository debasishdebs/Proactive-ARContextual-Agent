package com.rokid.context.work.frames.intent

import com.rokid.context.core.log.PcLog
import com.rokid.context.canvas.FrameIntentSink
import com.rokid.context.core.model.context.IntentLabel

/**
 * Writes a Live canvas publish's intent label to `frame_meta` through the sampler's store. Ruling: the sampler wins. The label is written only
 * when the photo has no label yet, by one conditional UPDATE ([IntentFrameStore.setIntentIfUnlabelled]: `intentLabel IS NULL` and no Forget
 * tombstone), so a sampler label is never overwritten, a forgotten frame never gets one and a Forget cannot slip in between a check and the write.
 * Unknown labels are dropped and confidence is clamped. The wipe fence is upstream: `LiveExtract.commit` compares the extraction's generation before
 * every `record`; this writer has no generation of its own. Never throws except cancellation.
 */
class FrameIntentWriter(private val store: IntentFrameStore) : FrameIntentSink {
    /** Rows stored by the last record (tests): 1 = written, 0 = already labelled, tombstoned or gone. */
    @Volatile var lastRows: Int = -1
        private set

    override suspend fun record(frameId: String, label: String, confidence: Float?, atMs: Long) {
        lastRows = -1
        // the interpreter lowercases its labels: match case-insensitively, still rejecting unknown ones
        val known = IntentLabel.entries.firstOrNull { it.name.equals(label.trim(), ignoreCase = true) } ?: return
        try {
            val conf = (confidence ?: 0f).let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }
            lastRows = store.setIntentIfUnlabelled(frameId, known.name, conf, atMs)
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            throw c
        } catch (e: Exception) {
            runCatching { PcLog.w("FrameIntentWriter", "intent write failed: ${e.javaClass.simpleName}") }
        }
    }
}
