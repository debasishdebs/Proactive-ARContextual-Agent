package com.rokid.context.orchestration.proactive

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.nudge.NudgeRunLock
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** A card as a live surface draws it. Built from the LIVE sources by the template at the moment of delivery; never stored. [sources] are the ids the Nexus sink checks again (memory ids, `frame:` cites, mark ids). */
data class DeliveredCard(val id: String, val title: String, val line: String, val sourceLabel: String, val sources: Set<String>)

/**
 * The live surfaces a delivered suggestion can reach besides the phone feed (which is derived from the store: a delivered, not withdrawn record IS the feed card). Every call is synchronous, cheap and never throws;
 * the production implementation is wired by the phone app (`ProactiveEnvironment.surfaces`). The default shows nothing.
 */
interface SuggestionSurfaces {
    /** Queues the card for the PCA glasses HUD (the hub draws it for 12 s). False = not accepted. */
    fun showHud(card: DeliveredCard): Boolean = false
    /** Queues the card for Nexus (its sink runs its own gates again). False = not accepted. */
    fun showNexus(card: DeliveredCard): Boolean = false
    /** Takes the records off every live surface (HUD overlay, Nexus notice) and drops the text. */
    fun withdraw(ids: Collection<String>) {}
    fun withdrawAll() {}
    /** Takes everything off ONE surface (its switch was turned off). */
    fun withdrawSurface(surface: Surface) {}

    companion object { val None: SuggestionSurfaces = object : SuggestionSurfaces {} }
}

/**
 * v3.1 delivery stage, AFTER the engine's would-show commit (design 2.4, rulings 2026-11-18 3: separate default-OFF switch per surface, the master must also be on). It re-checks the whole gate set at the moment of
 * delivery and the final gate step has NO suspension after it:
 *
 * 1. every read that suspends comes first (render the card from the live sources, provenance, marks, pins, the place's zone, the nudge count, then pause LAST);
 * 2. then ONE synchronous step under the driving commit fence ([ProactiveSeams.drivingCommit]) and the zone ticket's fence: master and surface switch, zone, not-worn (HUD and Nexus), the HUD host, verbosity, quiet
 * hours, the budget (without the record itself), the never list and snoozes, still-stored; a surface that passes claims its delivery ([SuggestionStore.markDelivered]: at most once per record and surface) and publishes.
 *
 * The card text is the TEMPLATE rendered again from the live sources; no provider is called. A throw is a drop. Logs: counts only.
 */
class ProactiveDelivery(
    private val db: ContextDatabase,
    private val provenance: ProvenanceGate,
    private val store: SuggestionStore,
    private val render: suspend (SuggestionRecord, Long) -> RenderedCard?,
    private val seams: ProactiveSeams,
    /** Master, family AND this surface's own switch (production: [ProactiveSettings.deliversTo]). Read again in the final step. */
    private val wanted: (Surface) -> Boolean,
    private val surfaces: () -> SuggestionSurfaces,
    private val gate: DataOpGate = DataOpGate.shared,
    private val budget: ProactiveBudget = ProactiveBudget(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val generation: () -> Long = { MemoryWipe.generation.get() },
) {
    /** Test seam: runs after the last suspending read and before the final step (a gate "closing between the engine commit and delivery"). */
    @Volatile internal var testBeforeFinal: (suspend () -> Unit)? = null

    suspend fun deliver(recordId: String, nowMs: Long = clock()): Map<Surface, Gate> {
        if (Surface.entries.none(wanted)) return emptyMap() // every switch off: nothing happens, nothing is counted
        val out = NudgeRunLock.mutex.withLock {
            try { pipeline(recordId, nowMs) } catch (c: CancellationException) { throw c } catch (e: Exception) {
                PcLog.i(TAG, "proactive delivery failed (${e.javaClass.simpleName})")
                Surface.entries.filter(wanted).associateWith { Gate.ERROR }
            }
        }
        var ok = 0; var failed = 0
        for ((s, g) in out) if (g == Gate.DELIVERED) { ok++; store.bump("deliver_" + s.token) } else { failed++; store.bump("drop_delivery_" + g.name.lowercase()) }
        PcLog.i(TAG, "proactive delivery ok=$ok failed=$failed")
        return out
    }

    private fun all(g: Gate, targets: List<Surface>) = targets.associateWith { g }

    private suspend fun pipeline(recordId: String, nowMs: Long): Map<Surface, Gate> {
        val ticket = seams.zoneGuard.ticket() // before the first suspension, like the engine and NexusGates
        val targets = Surface.entries.filter(wanted)
        val record = store.records().firstOrNull { it.id == recordId } ?: return all(Gate.WITHDRAWN, targets)
        val pending = targets.filter { it.token !in record.delivered }
        val result = LinkedHashMap<Surface, Gate>()
        targets.filter { it !in pending }.forEach { result[it] = Gate.ALREADY_DELIVERED }
        if (pending.isEmpty()) return result
        val hud0 = seams.hud() ?: return result + all(Gate.OFF, pending)
        val gen = generation()
        val lease = try { gate.lease(Leases.Kind.PROACTIVE) } catch (e: DataOpGate.LeaseBlocked) { return result + all(Gate.LEASE, pending) }
        try {
            lease.extend(SourceIds.refs(record.sourceIds))
            val block: suspend () -> Map<Surface, Gate> = block@{
                val dv = seams.drivingVersion() // before the driving state is read in the final step: a vehicle accepted since changes the version
                val card = render(record, nowMs) ?: return@block all(Gate.PROVENANCE, pending)
                if (provenance.check(SourceIds.provenance(record.sourceIds), gen) != ProvenanceGate.Verdict.Live) return@block all(Gate.PROVENANCE, pending)
                if (provenance.goneMarks(SourceIds.marks(record.sourceIds).toSet()).isNotEmpty() || !seams.pinsLive(SourceIds.pins(record.sourceIds))) return@block all(Gate.PROVENANCE, pending)
                if (try { seams.anchorBlocked(record.anchorKey) } catch (e: CancellationException) { throw e } catch (e: Exception) { true }) return@block all(Gate.ZONE_ANCHOR, pending)
                val globalToday = seams.globalToday(localMidnight(nowMs, hud0))
                // pause LAST among the suspending reads
                if (try { seams.paused() } catch (e: CancellationException) { throw e } catch (e: Exception) { true }) return@block all(Gate.PAUSE, pending)
                testBeforeFinal?.invoke()
                val delivered = DeliveredCard(record.id, card.title, card.line, card.sourceLabel, sourceIdsFor(record))
                // ---- final step: no suspension from here on ----
                var verdicts: Map<Surface, Gate> = all(Gate.COMMIT, pending)
                val write = { verdicts = finalStep(record, delivered, pending, nowMs, globalToday) }
                val ran = seams.drivingCommit(dv) { if (ticket != null) { if (ticket.fence.act(ticket) { write() } == null) verdicts = all(Gate.ZONE, pending) } else write() }
                if (ran) verdicts else all(Gate.DRIVING, pending)
            }
            val verdicts = when (val r = lease.commit(db, checkLive = true, block = block)) {
                is Leases.Commit.Committed -> r.value
                Leases.Commit.Fenced -> all(Gate.LEASE, pending)
            }
            return result + verdicts
        } finally {
            lease.close()
        }
    }

    /** Synchronous. Every gate that can be read without suspending, as of NOW, then the claim and the publication per surface. */
    private fun finalStep(record: SuggestionRecord, card: DeliveredCard, pending: List<Surface>, nowMs: Long, globalToday: Int): Map<Surface, Gate> {
        val hud = seams.hud() ?: return all(Gate.OFF, pending)
        val live = store.records()
        if (live.none { it.id == record.id }) return all(Gate.WITHDRAWN, pending)
        if (seams.zoneGuard.blocked()) return all(Gate.ZONE, pending)
        if (record.key in store.never() || FeedbackMath.blocked(record.key, store.feedback(), nowMs)) return all(Gate.FEEDBACK, pending)
        val budgetGate = budget.check(record.key, record.family, BudgetState(live.filter { it.id != record.id }, globalToday, seams.globalCap()), hud, nowMs)
        if (budgetGate != Gate.WOULD_SHOW) return all(budgetGate, pending)
        val worn = seams.worn()
        val nexus = seams.nexusHost()
        val out = LinkedHashMap<Surface, Gate>()
        for (s in pending) {
            out[s] = when {
                !wanted(s) -> Gate.SURFACE_OFF
                s != Surface.PHONE && !worn -> Gate.WEAR
                s == Surface.HUD && (nexus || hud.verbosity == Verbosity.SILENT) -> Gate.SURFACE_OFF
                s == Surface.NEXUS && !nexus -> Gate.SURFACE_OFF
                !store.markDelivered(record.id, s) -> Gate.ALREADY_DELIVERED
                else -> {
                    val shown = when (s) { Surface.PHONE -> true; Surface.HUD -> surfaces().showHud(card); Surface.NEXUS -> surfaces().showNexus(card) }
                    if (shown) Gate.DELIVERED else Gate.COMMIT
                }
            }
        }
        return out
    }

    private fun sourceIdsFor(r: SuggestionRecord): Set<String> = (SourceIds.memories(r.sourceIds) + SourceIds.frames(r.sourceIds).map { SourceIds.frame(it) } + SourceIds.marks(r.sourceIds)).toSet()

    private fun localMidnight(nowMs: Long, hud: com.rokid.context.orchestration.nudge.NudgeHud): Long = Instant.ofEpochMilli(nowMs).atZone(hud.zone).toLocalDate().atStartOfDay(hud.zone).toInstant().toEpochMilli()

    companion object { private const val TAG = "PcProactive" }
}

/**
 * The user's answers to a delivered suggestion, from ANY surface (phone feed, Settings list, HUD head-motion dismissal, Nexus Dismiss): they write the feedback counters and then withdraw the record from EVERY surface
 * (store -> phone feed, HUD overlay, Nexus notice). Plain counters; ids and tokens only.
 */
class SuggestionActions(
    private val store: () -> SuggestionStore,
    private val surfaces: () -> SuggestionSurfaces,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun withdraw(id: String) { store().withdraw(id); try { surfaces().withdraw(listOf(id)) } catch (e: Exception) { PcLog.i(TAG, "withdraw failed") } }

    /** Dismiss: the key's multiplier drops and it rests 24 h. */
    fun dismiss(id: String): Boolean = act(id, FeedbackAction.DISMISS, withdraw = true)
    /** Snooze one day. */
    fun snooze(id: String): Boolean = act(id, FeedbackAction.SNOOZE, withdraw = true)
    /** "Never suggest this": the KEY joins the never list (it survives Forget of unrelated data, as ruled; Delete all clears it). */
    fun never(id: String): Boolean {
        val s = store(); val r = s.records().firstOrNull { it.id == id } ?: return false
        val ok = s.addNever(r.key)
        s.recordFeedback(FeedbackAction.NEVER, r.key, r.family, clock())
        withdraw(id)
        return ok
    }
    fun thumb(id: String, up: Boolean): Boolean = act(id, if (up) FeedbackAction.UP else FeedbackAction.DOWN, withdraw = false)

    private fun act(id: String, a: FeedbackAction, withdraw: Boolean): Boolean {
        val s = store(); val r = s.records().firstOrNull { it.id == id } ?: return false
        val ok = s.recordFeedback(a, r.key, r.family, clock())
        if (withdraw) withdraw(id)
        return ok
    }

    companion object { private const val TAG = "PcProactive" }
}
