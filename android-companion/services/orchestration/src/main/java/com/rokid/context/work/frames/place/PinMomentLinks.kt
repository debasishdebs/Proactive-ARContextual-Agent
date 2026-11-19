package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPinStore.Arming
import com.rokid.context.memory.place.UserPlace
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The pins a Forget would delete, for its confirmation: how many, and the first [NAMES_SHOWN] names as the pin list shows them. UI only: a name never goes into a log. */
data class PinsGoing(val count: Int, val names: List<String>) {
    companion object { const val NAMES_SHOWN = 5; val NONE = PinsGoing(0, emptyList()) }
}

/** The pin removal of an explicit Forget could not be prepared (the durable arming was not stored or not readable back): the Forget is NOT run. */
class PinArmFailed : Exception("pin removal could not be prepared")

/**
 * Slice 1.4.2 "Pin to Moment": what becomes of a pin whose reference Moment goes away (the link is an 8-hex Moment id on the pin, prefs `place_pins`). Two kinds of removal, two fates:
 *
 *  - an EXPLICIT user Forget (a photo, a Moment, a Scene, a time range, a multi-select) or Delete all (also its crash resume): the pin is FORGOTTEN TOO. [explicit] stores a durable ARMING that belongs to
 *    that ONE operation and names the exact (pin, Moment) pairs it may delete (pins whose Moment is alive when it starts). The arming is marked committed only when the Forget (or the wipe epoch) really
 *    committed; the cascade then deletes only the armed pairs whose Moment is gone (and, for a range, inside the previewed range). A crash between commit and cascade is finished at the next start
 *    ([settle]) from a COMMITTED arming; an arming that never committed is dropped, never acted on, and a stale one never survives into the next operation or blocks the sweeps.
 *  - every other removal (retention expiry, the suspect-photo sweep, the invalid-Moment purge, ...): the pin is NEVER deleted; only its LINK is cleared and the pin shows "Needs a Moment".
 *
 * A Moment counts as gone when no stored photo of its second is left, or its second is tombstoned (`MomentDao.photoMomentsOf`): the rebuildable projection row is NOT the test (a forgotten fact only drops
 * and rebuilds it). Log lines are literals plus a count: never an id, name, note or position.
 */
object PinMomentLinks {
    /** Explicit operations (a Forget, a wipe) running in this process: the non-explicit sweep leaves the pins alone while one runs. */
    private val inflight = AtomicInteger(0)

    private fun momentsOf(context: Context): suspend (List<String>) -> Set<String> = { ids -> MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet() }

    // ---- explicit Forget ----

    /**
     * Runs [block] (an explicit Forget, inside the DataOpGate) with the cascade around it. [reached] limits the cascade to Moments the Forget really targets (a time range); [committed] says whether the Forget's
     * result means it committed. Throws [PinArmFailed] BEFORE running [block] when pins reference live Moments and the arming cannot be stored.
     */
    suspend fun <T> explicit(context: Context, reached: (String) -> Boolean = { true }, committed: (T) -> Boolean = { true }, block: suspend () -> T): T =
        explicitStore(PlaceGraph.pins(context), momentsOf(context), { PlaceGraph.savePins(context, it) }, reached, committed, block)

    internal suspend fun <T> explicitStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, reached: (String) -> Boolean, committed: (T) -> Boolean, block: suspend () -> T): T {
        inflight.incrementAndGet()
        try {
            val op = UUID.randomUUID().toString()
            PlacePinning.underPinLock {
                resolveStore(store, alive, save) // an earlier operation's leftovers are settled (committed) or dropped (not), never carried over
                val pairs = livePairs(store, alive)
                if (pairs.isNotEmpty() && !store.arm(Arming(op, "forget", false, pairs))) { store.disarm(); throw PinArmFailed() }
            }
            val r = try { block() } catch (t: Throwable) { PlacePinning.underPinLock { dropIfOp(store, op) }; throw t }
            PlacePinning.underPinLock {
                if (committed(r)) { store.markCommitted(op); finishOp(store, op, alive, save, reached) } else dropIfOp(store, op)
            }
            return r
        } finally { inflight.decrementAndGet() }
    }

    private suspend fun livePairs(store: UserPinStore, alive: suspend (List<String>) -> Set<String>): Map<String, String> {
        val linked = store.pins().filter { it.momentId != null }
        if (store.corrupt || linked.isEmpty()) return emptyMap()
        val live = alive(linked.mapNotNull { it.momentId }.distinct())
        return linked.filter { it.momentId in live }.associate { it.id to it.momentId!! }
    }

    private fun dropIfOp(store: UserPinStore, op: String) { if (store.arming()?.op == op) store.disarm() }

    /** Deletes the armed pairs of [op] whose Moment is gone (and [reached]), disarms, then clears every other dead link. A failed save leaves the committed arming for the next start. */
    private suspend fun finishOp(store: UserPinStore, op: String, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, reached: (String) -> Boolean) {
        val a = store.arming()?.takeIf { it.op == op } ?: return
        val n = deleteDead(store, a.pairs, alive, save, reached) ?: return
        store.disarm()
        if (n > 0) PcLog.i("PcPlace", "pin deleted with its forgotten moment count=$n")
        sweepStore(store, alive)
    }

    /** Deletes the pins of [pairs] (same pin, same Moment) whose Moment is gone. Null = unreadable list or not saved. */
    private suspend fun deleteDead(store: UserPinStore, pairs: Map<String, String>, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, reached: (String) -> Boolean): Int? {
        val pins = store.pins()
        if (store.corrupt) return null
        val cand = pins.filter { pairs[it.id] != null && it.momentId == pairs[it.id] }
        if (cand.isEmpty()) return 0
        val live = alive(cand.mapNotNull { it.momentId }.distinct())
        val dead = cand.filter { it.momentId !in live && reached(it.momentId!!) }.map { it.id }.toSet()
        if (dead.isNotEmpty() && !save(pins.filter { it.id !in dead })) return null
        return dead.size
    }

    /** Start-up and sweeps: a COMMITTED arming is finished, one that never committed (or is unreadable) is dropped. Returns the pins deleted. */
    internal suspend fun resolveStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean): Int {
        val a = store.arming()
        if (a == null) { if (store.hasArmingRecord()) store.disarm(); return 0 }
        if (!a.committed) { store.disarm(); return 0 }
        val n = deleteDead(store, a.pairs, alive, save, { true }) ?: return 0 // not finished: stays armed, retried at the next start
        store.disarm()
        if (n > 0) PcLog.i("PcPlace", "pin deleted with its forgotten moment count=$n")
        return n
    }

    // ---- Delete all ----

    /** Arms every pin that has a link for the wipe about to start (a resume keeps its earlier committed arming and adds the current ones). Throws [PinArmFailed] before anything is deleted. Pair with [endWipe]. */
    suspend fun beginWipe(context: Context, resume: Boolean): String = beginWipeStore(PlaceGraph.pins(context), momentsOf(context), { PlaceGraph.savePins(context, it) }, resume)

    internal suspend fun beginWipeStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, resume: Boolean): String {
        inflight.incrementAndGet()
        val op = UUID.randomUUID().toString()
        try {
            PlacePinning.underPinLock {
                val old = store.arming()
                if (!resume) resolveStore(store, alive, save)
                val linked = store.pins().filter { it.momentId != null }.associate { it.id to it.momentId!! }
                val keep = if (resume && old?.committed == true) old.pairs else emptyMap()
                val pairs = keep + linked
                if (pairs.isNotEmpty() && !store.arm(Arming(op, "wipe", resume, pairs)) && !resume) { store.disarm(); throw PinArmFailed() }
            }
        } catch (t: Throwable) { inflight.decrementAndGet(); throw t }
        return op
    }

    /** The wipe's epoch committed: its arming may now be acted on (also after a crash). */
    suspend fun wipeCommitted(context: Context, op: String) { PlacePinning.underPinLock { PlaceGraph.pins(context).markCommitted(op) } }

    /** The epoch could NOT be committed: nothing was deleted, so the arming is dropped (the pins stay). */
    suspend fun abortWipe(context: Context, op: String) { PlacePinning.underPinLock { dropIfOp(PlaceGraph.pins(context), op) } }

    fun endWipe() { inflight.decrementAndGet() }

    // ---- settling, sweeps ----

    /** Finishes a committed pending arming (a crash between a Forget's commit and its cascade, an interrupted wipe) and drops one that never committed; then clears dead links. Null = list unreadable or not rewritten. */
    suspend fun settle(context: Context): Int? = PlacePinning.underPinLock {
        val store = PlaceGraph.pins(context)
        val deleted = resolveStore(store, momentsOf(context)) { PlaceGraph.savePins(context, it) }
        sweepStore(store, momentsOf(context)) ?: return@underPinLock null
        deleted
    }

    /** Non-explicit removal: settles any leftover arming (so it never blocks), then clears every link whose Moment is gone (the pins stay). Skipped while an explicit operation runs in this process. */
    suspend fun sweep(context: Context): Int? = sweepGuarded(PlaceGraph.pins(context), momentsOf(context)) { PlaceGraph.savePins(context, it) }

    internal suspend fun sweepGuarded(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean): Int? {
        if (inflight.get() > 0) return 0
        return PlacePinning.underPinLock { sweepAll(store, alive, save) }
    }

    internal suspend fun sweepAll(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean): Int? {
        resolveStore(store, alive, save)
        return sweepStore(store, alive)
    }

    internal fun wipeCommittedStore(store: UserPinStore, op: String) = store.markCommitted(op)
    internal fun abortWipeStore(store: UserPinStore, op: String) = dropIfOp(store, op)

    internal suspend fun sweepStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>): Int? {
        val linked = store.pins().mapNotNull { it.momentId }.toSet()
        if (store.corrupt) return null
        if (linked.isEmpty()) return 0
        val cleared = store.clearMomentsExcept(alive(linked.toList())) ?: return null
        val n = cleared.size
        if (n > 0) PcLog.i("PcPlace", "pin moment link cleared by removal count=$n")
        return n
    }

    // ---- confirmation ----

    /**
     * For the confirmation of an explicit Forget: how many pins would be deleted with it. [reach] answers which of the linked LIVE Moment ids the Forget leaves without a photo (null = every one: Delete all).
     * Read only. Never throws (NONE when unreadable).
     */
    suspend fun pinsGoing(context: Context, reach: (suspend (List<String>) -> Set<String>)? = null): PinsGoing = try {
        val pins = PlaceGraph.pins(context).pins().filter { it.momentId != null }
        if (pins.isEmpty()) PinsGoing.NONE
        else {
            val ids = pins.mapNotNull { it.momentId }.distinct()
            val live = MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet()
            val going = if (reach == null) ids.toSet() else reach(live.toList())
            pins.filter { it.momentId in going }.let { g -> PinsGoing(g.size, g.take(PinsGoing.NAMES_SHOWN).map { it.name }) }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PinsGoing.NONE }

    /** Registered once at start (ContextApp). Never throws: a failed sweep is repeated by the next removal, the next screen visit and the next start. */
    fun listener(context: Context): ForgetListener {
        val app = context.applicationContext
        return listenerOf({ sweep(app) }, { settle(app) })
    }

    internal fun listenerOf(sweep: suspend () -> Unit, deleteAll: suspend () -> Unit): ForgetListener = object : ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) { safely(sweep) }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { safely(sweep) }
        override suspend fun onUserForget() { safely(sweep) }
        override suspend fun onDeleteAll() { safely(deleteAll) }
    }

    private suspend fun safely(f: suspend () -> Unit) { try { f() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w("PcPlace", "pin moment sweep failed") } }
}
