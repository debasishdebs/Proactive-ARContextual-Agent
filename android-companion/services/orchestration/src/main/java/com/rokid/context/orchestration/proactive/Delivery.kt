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

/** How [commitFenced] ended. */
internal enum class FenceOutcome { RAN, ZONE, DRIVING }

/**
 * v3.1 r3 P1-1: THE lock order, zone fence -> driving commit monitor -> store lock, in one place. The zone fence (when there is a [ticket]) is taken FIRST, the driving commit fence INSIDE it, [write] inside both. Everything that reads
 * the driving state without writing ([DrivingSignal.blocks], [DrivingSignal.assess], [DrivingSignal.version]) is lock-free, so the Nexus sink (which holds the fences) never waits for the monitor.
 */
internal fun ProactiveSeams.commitFenced(ticket: com.rokid.context.memory.place.ZoneTicket?, expectedVersion: Long, write: () -> Unit): FenceOutcome {
    var outcome = FenceOutcome.DRIVING
    val inner = { if (drivingCommit(expectedVersion, write)) outcome = FenceOutcome.RAN }
    if (ticket != null) { if (ticket.fence.act(ticket) { inner() } == null) return FenceOutcome.ZONE } else inner()
    return outcome
}

/** A card as a live surface draws it. Built from the LIVE sources by the template at the moment of delivery; never stored. [sources] are the ids the Nexus sink checks again (memory ids, `frame:` cites, mark ids). */
data class DeliveredCard(val id: String, val title: String, val line: String, val sourceLabel: String, val sources: Set<String>,
    /** Time from publication to the next quiet-hours start (null = no window): the glasses' hard cap is derived from it (v3.1 r5 P1-2). Set at the moment of publication. */
    val quietInMs: Long? = null)

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
    /** The claim -> publish -> withdrawn state machine every withdrawal path also goes through. */
    private val publications: SuggestionPublications = SuggestionPublications.shared,
    /** Armed after every publication: the next quiet-hours start withdraws what is displayed (process-wide in production). */
    private val quietWatch: QuietBoundaryWatch = QuietBoundaryWatch.shared,
) {
    /** Test seam: runs after the last suspending read and before the final step (a gate "closing between the engine commit and delivery"). */
    @Volatile internal var testBeforeFinal: (suspend () -> Unit)? = null
    /** Test seam: runs INSIDE the fences, first thing in the final step (a pause or a switch landing after the fences were taken). */
    @Volatile internal var testInFence: (() -> Unit)? = null
    /** Test seam: runs between a surface's claim and its publication (a withdrawal landing in between). */
    @Volatile internal var testBetweenClaimAndPublish: (() -> Unit)? = null
    /** Test seam: runs between the ledger reservation and the claims (a process death in between: a throw here). */
    @Volatile internal var testAfterReserve: (() -> Unit)? = null

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
                when (seams.commitFenced(ticket, dv, write)) { FenceOutcome.RAN -> verdicts; FenceOutcome.ZONE -> all(Gate.ZONE, pending); FenceOutcome.DRIVING -> all(Gate.DRIVING, pending) }
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

    /** The synchronous pause read for the final step; a throw is a pause (fail closed). */
    private fun pausedNow(): Boolean = try { seams.pausedNow() } catch (e: Exception) { true }

    private fun quiet(hud: com.rokid.context.orchestration.nudge.NudgeHud, nowMs: Long) = hud.quietHours?.let { com.rokid.context.core.model.QuietWindow.occurrenceAt(it, nowMs, hud.zone) } != null

    /**
     * Synchronous, INSIDE the driving commit fence and the zone ticket's fence. Every gate again as of NOW (a fresh clock: quiet hours and the budget are judged at this instant, not at the delivery's entry; the pause is
     * read here too), then per surface the claim ([SuggestionPublications] and [SuggestionStore.markDelivered]) and the publication, one step against a withdrawal.
     */
    private fun finalStep(record: SuggestionRecord, card: DeliveredCard, pending: List<Surface>, entryMs: Long, globalToday: Int): Map<Surface, Gate> {
        testInFence?.invoke()
        val nowMs = maxOf(entryMs, clock())
        val hud = seams.hud() ?: return all(Gate.OFF, pending)
        val live = store.records()
        val stored = live.firstOrNull { it.id == record.id }
        if (stored == null || publications.isWithdrawn(record.id)) return all(Gate.WITHDRAWN, pending)
        if (seams.zoneGuard.blocked()) return all(Gate.ZONE, pending)
        if (pausedNow()) return all(Gate.PAUSE, pending)
        if (localMidnight(nowMs, hud) != localMidnight(entryMs, hud)) return all(Gate.COMMIT, pending) // the day turned during the reads: the nudge count is stale
        if (record.key in store.never() || FeedbackMath.blocked(record.key, store.feedback(), nowMs)) return all(Gate.FEEDBACK, pending)
        val budgetGate = budget.check(record.key, record.family, BudgetState(live.filter { it.id != record.id }, globalToday, seams.globalCap(), store.ledger()), hud, nowMs)
        if (budgetGate != Gate.WOULD_SHOW) return all(budgetGate, pending)
        val worn = seams.worn()
        val nexus = seams.nexusHost()
        val first = stored.delivered.isEmpty()
        val out = LinkedHashMap<Surface, Gate>()
        val ready = ArrayList<Surface>()
        for (s in pending) {
            val g = when {
                !wanted(s) -> Gate.SURFACE_OFF
                s != Surface.PHONE && !worn -> Gate.WEAR
                s == Surface.HUD && (nexus || hud.verbosity == Verbosity.SILENT) -> Gate.SURFACE_OFF
                s == Surface.NEXUS && !nexus -> Gate.SURFACE_OFF
                else -> null
            }
            if (g != null) out[s] = g else ready += s
        }
        // Codex v3.1 r2 P1-3: the budget is RESERVED first (a RESERVED ledger line, counted like a delivered one), then the surfaces claim and publish, then the line is confirmed. A failed reservation is NO delivery (fail closed);
        // a publication that fails keeps the line (conservative) unless nothing was claimed at all; a death between the phases leaves a RESERVED line that every budget still counts.
        var reserved: LedgerEntry? = null
        if (first && ready.isNotEmpty()) {
            val e = LedgerEntry(nowMs, record.family, LedgerKind.RESERVED)
            if (!(try { store.appendLedger(e) } catch (x: Exception) { false })) { PcLog.i(TAG, "proactive ledger reserve failed"); store.bump("drop_ledger_reserve"); ready.forEach { out[it] = Gate.COMMIT }; return pending.associateWith { out[it] ?: Gate.COMMIT } }
            reserved = e
            testAfterReserve?.invoke()
        }
        for (s in ready) out[s] = claimAndPublish(record, s, card)
        if (reserved != null) {
            if (out.values.any { it == Gate.DELIVERED }) { if (!store.confirmLedger(reserved)) store.bump("ledger_confirm_failed") } // stays RESERVED: counts the same
            else if (ready.all { out[it] == Gate.WITHDRAWN || out[it] == Gate.ALREADY_DELIVERED || out[it] == Gate.SURFACE_OFF } && store.records().firstOrNull { it.id == record.id }?.delivered?.isEmpty() != false) store.releaseLedger(reserved) // nothing was claimed or shown
        }
        return pending.associateWith { out[it] ?: Gate.COMMIT }
    }

    private fun claimAndPublish(record: SuggestionRecord, s: Surface, card: DeliveredCard): Gate {
        val claim = publications.claim(record.id, s) ?: return Gate.WITHDRAWN
        if (!store.markDelivered(record.id, s)) return Gate.ALREADY_DELIVERED
        testBetweenClaimAndPublish?.invoke()
        var shown = false
        var quietHit = false
        val ran = publications.publish(claim) {
            // v3.1 r5 P1-1: quiet hours judged AGAIN at the moment of publication on a fresh clock (the persistence above may have crossed the boundary); fail closed when the host is unreadable
            val now = clock(); val hud = try { seams.hud() } catch (e: Exception) { null }
            if (hud == null || quiet(hud, now)) { quietHit = true; false } else {
                val live = card.copy(quietInMs = hud.quietHours?.let { q -> QuietBoundaryWatch.nextQuietStart(q, now, hud.zone)?.minus(now) })
                shown = when (s) { Surface.PHONE -> true; Surface.HUD -> surfaces().showHud(live); Surface.NEXUS -> surfaces().showNexus(live) }
                shown
            }
        }
        if (quietHit) return Gate.QUIET_HOURS
        if (ran) {
            // a card is displayed: watch for the next quiet-hours boundary; arming inside an active window withdraws at once, and so does a boundary crossed since the publication
            try { quietWatch.arm(seams.hud(), clock()) } catch (e: Exception) { }
            val crossed = try { seams.hud()?.let { quiet(it, clock()) } ?: true } catch (e: Exception) { true }
            if (crossed) { publications.withdraw(listOf(record.id)); try { surfaces().withdraw(listOf(record.id)) } catch (e: Exception) { PcLog.i(TAG, "withdraw failed") }; PcLog.i(TAG, "proactive card withdrawn at quiet boundary"); return Gate.WITHDRAWN }
        }
        return when { ran -> Gate.DELIVERED; publications.isWithdrawn(record.id) -> Gate.WITHDRAWN; shown || publications.isLive(claim) -> Gate.COMMIT; else -> Gate.SURFACE_OFF }
    }

    /**
     * The publish point of a QUEUED surface (Nexus; Codex v3.1 r1 P1-1): everything is checked again, as of now, under the driving commit fence and the zone ticket's fence: the master and the three switches, the driving
     * state (UNKNOWN is driving), pause (a suspending read first, then the synchronous one inside the fences), the zone, quiet hours with a fresh clock, worn, the host and that the record is still stored, delivered
     * there and not withdrawn. False = drop it.
     */
    suspend fun publishable(recordId: String, surface: Surface): Boolean {
        if (!wanted(surface) || publications.isWithdrawn(recordId)) return false
        return try {
            val ticket = seams.zoneGuard.ticket()
            if (try { seams.paused() } catch (e: CancellationException) { throw e } catch (e: Exception) { true }) return false
            val dv = seams.drivingVersion()
            var ok = false
            val check = { ok = publishCheck(recordId, surface) }
            seams.commitFenced(ticket, dv, check) == FenceOutcome.RAN && ok
        } catch (c: CancellationException) { throw c } catch (e: Exception) { false }
    }

    /**
     * The SYNCHRONOUS publish gate the Nexus sink asks INSIDE its final admission (its fence pass, the last step before the transport work is queued; and again right before the call), Codex v3.1 r2 P1-1. Everything again,
     * as of now: driving (UNKNOWN is driving; read LOCK-FREE from the tracker's published snapshot: the sink already holds the zone fences, and no driving monitor may be taken under them), the master and the Nexus switch, pause, zone, quiet hours with a fresh clock, worn, the host,
     * stored, delivered there and not withdrawn. A throw is a drop.
     */
    fun publishableNow(recordId: String, surface: Surface): Boolean = try { !seams.driving() && publishCheck(recordId, surface) } catch (e: Exception) { false }

    private fun publishCheck(id: String, surface: Surface): Boolean {
        if (!wanted(surface) || publications.isWithdrawn(id)) return false
        val hud = seams.hud() ?: return false
        if (seams.zoneGuard.blocked() || pausedNow() || quiet(hud, clock())) return false
        if (surface != Surface.PHONE && !seams.worn()) return false
        val nexus = seams.nexusHost()
        if (surface == Surface.HUD && (nexus || hud.verbosity == Verbosity.SILENT)) return false
        if (surface == Surface.NEXUS && !nexus) return false
        val r = store.records().firstOrNull { it.id == id } ?: return false
        return surface.token in r.delivered
    }

    /** One card the phone feed shows: the stored record and the template rendered from its LIVE sources just now. */
    data class FeedCard(val record: SuggestionRecord, val card: RenderedCard)

    /**
     * What the Home feed may show right now (Codex v3.1 r1 P1-2). The same fences and gates as a delivery at EVERY render: the phone switches, the read lease, provenance of every source, the zone of the place, pause,
     * driving (UNKNOWN is driving), the zone fence and quiet hours with a fresh clock. Any gate closed = nothing (never previously delivered text). Never throws.
     */
    suspend fun phoneFeed(nowMs: Long = clock()): List<FeedCard> {
        if (!wanted(Surface.PHONE)) return emptyList()
        return try { feedPipeline(nowMs) } catch (c: CancellationException) { throw c } catch (e: Exception) {
            PcLog.i(TAG, "proactive feed failed (${e.javaClass.simpleName})")
            emptyList()
        }
    }

    private suspend fun feedPipeline(nowMs: Long): List<FeedCard> {
        val ticket = seams.zoneGuard.ticket() // before the first suspension
        val shown = store.records().filter { Surface.PHONE.token in it.delivered && !publications.isWithdrawn(it.id) }
        if (shown.isEmpty() || seams.hud() == null) return emptyList()
        val gen = generation()
        val lease = try { gate.lease(Leases.Kind.PROACTIVE) } catch (e: DataOpGate.LeaseBlocked) { return emptyList() }
        try {
            lease.extend(shown.flatMap { SourceIds.refs(it.sourceIds) })
            val block: suspend () -> List<FeedCard> = block@{
                val dv = seams.drivingVersion()
                val ok = ArrayList<FeedCard>()
                for (r in shown) {
                    val card = render(r, nowMs) ?: continue
                    if (provenance.check(SourceIds.provenance(r.sourceIds), gen) != ProvenanceGate.Verdict.Live) continue
                    if (provenance.goneMarks(SourceIds.marks(r.sourceIds).toSet()).isNotEmpty() || !seams.pinsLive(SourceIds.pins(r.sourceIds))) continue
                    if (try { seams.anchorBlocked(r.anchorKey) } catch (e: CancellationException) { throw e } catch (e: Exception) { true }) continue
                    ok += FeedCard(r, card)
                }
                if (ok.isEmpty()) return@block emptyList()
                if (try { seams.paused() } catch (e: CancellationException) { throw e } catch (e: Exception) { true }) return@block emptyList()
                testBeforeFinal?.invoke()
                var out: List<FeedCard> = emptyList()
                val write = { out = feedFinal(ok, nowMs) }
                if (seams.commitFenced(ticket, dv, write) == FenceOutcome.RAN) out else emptyList()
            }
            return when (val r = lease.commit(db, checkLive = true, block = block)) {
                is Leases.Commit.Committed -> r.value
                Leases.Commit.Fenced -> emptyList()
            }
        } finally {
            lease.close()
        }
    }

    /** Synchronous, inside the fences. */
    private fun feedFinal(cards: List<FeedCard>, entryMs: Long): List<FeedCard> {
        testInFence?.invoke()
        if (!wanted(Surface.PHONE)) return emptyList()
        val hud = seams.hud() ?: return emptyList()
        if (seams.zoneGuard.blocked() || pausedNow() || quiet(hud, maxOf(entryMs, clock()))) return emptyList()
        val live = store.records().associateBy { it.id }
        return cards.filter { c -> live[c.record.id]?.let { Surface.PHONE.token in it.delivered } == true && !publications.isWithdrawn(c.record.id) }
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
    private val publications: SuggestionPublications = SuggestionPublications.shared,
) {
    /** The record is marked WITHDRAWN first (a delivery between its claim and its publication then loses), then removed from the store and every surface. */
    private fun withdraw(id: String) { publications.withdraw(listOf(id)); store().withdraw(id); try { surfaces().withdraw(listOf(id)) } catch (e: Exception) { PcLog.i(TAG, "withdraw failed") } }

    /** Dismiss: the key's multiplier drops and it rests 24 h; the dismissal also goes to the ledger (five in 24 h pause delivery for 24 h). */
    fun dismiss(id: String): Boolean = act(id, FeedbackAction.DISMISS, withdraw = true)
    /** Snooze one day. */
    fun snooze(id: String): Boolean = act(id, FeedbackAction.SNOOZE, withdraw = true)
    /** "Never suggest this": the KEY joins the never list (it survives Forget of unrelated data, as ruled; Delete all clears it). */
    fun never(id: String): Boolean {
        val s = store(); val r = s.records().firstOrNull { it.id == id } ?: return false
        publications.withdraw(listOf(id))
        s.appendLedger(LedgerEntry(clock(), r.family, LedgerKind.DISMISSED)) // write-ahead (r3 P1-3): the dismissal is durable (or its marker is) before anything else
        val ok = s.addNever(r.key)
        s.recordFeedback(FeedbackAction.NEVER, r.key, r.family, clock())
        withdraw(id)
        return ok
    }
    fun thumb(id: String, up: Boolean): Boolean = act(id, if (up) FeedbackAction.UP else FeedbackAction.DOWN, withdraw = false)

    private fun act(id: String, a: FeedbackAction, withdraw: Boolean): Boolean {
        val s = store(); val r = s.records().firstOrNull { it.id == id } ?: return false
        if (withdraw) publications.withdraw(listOf(id))
        if (a == FeedbackAction.DISMISS) s.appendLedger(LedgerEntry(clock(), r.family, LedgerKind.DISMISSED)) // write-ahead (r3 P1-3): durable, or fail closed with a marker, BEFORE the card is withdrawn
        val ok = s.recordFeedback(a, r.key, r.family, clock())
        if (withdraw) withdraw(id)
        return ok
    }

    companion object { private const val TAG = "PcProactive" }
}
