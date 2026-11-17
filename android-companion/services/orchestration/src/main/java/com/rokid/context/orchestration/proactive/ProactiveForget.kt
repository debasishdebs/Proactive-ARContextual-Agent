package com.rokid.context.orchestration.proactive

import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.provenance.ProvenanceGate

/** Which of a record's source ids are gone (a forgotten memory or photo, a deleted mark, a removed pin). */
class SourceLiveness(private val provenance: ProvenanceGate, private val pinIds: () -> Set<String>?) {
    suspend fun dead(sourceIds: Collection<String>): Set<String> {
        val dead = HashSet<String>()
        provenance.goneMemories(SourceIds.memories(sourceIds).toSet()).forEach { dead += SourceIds.mem(it) }
        provenance.goneFrames(SourceIds.frames(sourceIds).toSet(), requirePresent = false).forEach { dead += SourceIds.frame(it) }
        provenance.goneMarks(SourceIds.marks(sourceIds).toSet()).forEach { dead += SourceIds.mark(it) }
        val pins = SourceIds.pins(sourceIds)
        if (pins.isNotEmpty()) { val live = pinIds(); pins.filter { live == null || it !in live }.forEach { dead += SourceIds.pin(it) } }
        return dead
    }
}

/**
 * Per-suggestion Forget linkage (design 2.4, ruling 6). Any user Forget, a photo or range forget and retention expiry withdraw every record that cites a source that is no longer live, so a forgotten place memory,
 * pin or mark cannot come back as a suggestion; when liveness cannot be read, every record is withdrawn (fail closed; the never list stays). Delete all clears the store (records, the never list, counters) and
 * resets the switches. Nothing is delivered in v3.0, so there is no live surface to withdraw.
 */
class ProactiveForgetListener(
    private val store: () -> SuggestionStore,
    private val liveness: () -> SourceLiveness,
    private val onDeleteAll: () -> Unit = {},
) : ForgetListener {
    override suspend fun onUserForget() { sweep() }
    override suspend fun onFramesForgotten(frameIds: List<String>) { sweep() }
    override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { sweep() }
    override suspend fun onDeleteAll() { store().clearAll(); onDeleteAll.invoke() }

    suspend fun sweep(): Int {
        val s = store()
        val all = s.records().flatMap { it.sourceIds }.toSet()
        if (all.isEmpty()) return 0
        val dead = try { liveness().dead(all) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (e: Exception) { return s.withdrawWhere { true } }
        if (dead.isEmpty()) return 0
        return s.withdrawWhere { r -> r.sourceIds.any { it in dead } }
    }
}
