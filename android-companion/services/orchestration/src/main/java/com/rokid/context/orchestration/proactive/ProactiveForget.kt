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
 * resets the switches. A withdrawn record is also taken off every live surface (v3.1).
 */
class ProactiveForgetListener(
    private val store: () -> SuggestionStore,
    private val liveness: () -> SourceLiveness,
    private val onDeleteAll: () -> Unit = {},
    /** v3.1: the live surfaces (HUD overlay, Nexus notice). The phone feed follows the store, so removing a record from it also removes the card and its rendered text. */
    private val surfaces: () -> SuggestionSurfaces = { SuggestionSurfaces.None },
    private val publications: SuggestionPublications = SuggestionPublications.shared,
) : ForgetListener {
    override suspend fun onUserForget() { sweep() }
    override suspend fun onFramesForgotten(frameIds: List<String>) { sweep() }
    override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { sweep() }
    override suspend fun onDeleteAll() { publications.withdrawAll(); publications.withdraw(store().records().map { it.id }); store().clearAll(); safe { surfaces().withdrawAll() }; onDeleteAll.invoke() }

    private inline fun safe(f: () -> Unit) { try { f() } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (e: Exception) { /* a surface that cannot be told is closed by its own gates and its 12 s expiry */ } }

    suspend fun sweep(): Int {
        val s = store()
        val all = s.records().flatMap { it.sourceIds }.toSet()
        if (all.isEmpty()) return 0
        val before = s.records()
        val dead = try { liveness().dead(all) } catch (c: kotlin.coroutines.cancellation.CancellationException) { throw c } catch (e: Exception) {
            publications.withdraw(before.map { it.id })
            val n = s.withdrawWhere { true }; safe { surfaces().withdraw(before.map { it.id }) }; return n
        }
        if (dead.isEmpty()) return 0
        publications.withdraw(before.filter { r -> r.sourceIds.any { it in dead } }.map { it.id }) // first: a delivery between its claim and its publication loses
        val n = s.withdrawWhere { r -> r.sourceIds.any { it in dead } }
        safe { surfaces().withdraw(before.filter { r -> r.sourceIds.any { it in dead } }.map { it.id }) } // the HUD overlay and the Nexus notice go too, at once
        return n
    }
}
