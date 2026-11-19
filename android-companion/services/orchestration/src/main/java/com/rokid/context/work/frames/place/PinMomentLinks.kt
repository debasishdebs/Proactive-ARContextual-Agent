package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPlace

/**
 * Slice 1.4.2 "Pin to Moment": what becomes of a pin whose reference Moment goes away (the link is an 8-hex Moment id on the pin, prefs `place_pins`). Two kinds of removal, two fates:
 *
 *  - an EXPLICIT user Forget (a photo, a Moment, a Scene, a time range, a multi-select) or Delete all (also its crash resume): the pin is FORGOTTEN TOO, like a child of the Moment. [explicit] arms the pins
 *    that reference a live Moment (durably, ids only) BEFORE the Forget runs and [cascadeAfter] deletes the armed pins whose Moment is gone once it committed; a crash in between is finished at the next
 *    start (`ForgetStartup`) from the arming.
 *  - every other removal (retention expiry, the suspect-photo sweep, the invalid-Moment purge, ...): the pin is NEVER deleted; only its LINK is cleared and the pin shows "Needs a Moment". Those paths tell
 *    the [listener]; an armed (explicit) run in flight or pending is left to its own cascade. Retention and the sweeps run under the exclusive DataOpGate, so they never interleave with an explicit Forget.
 *
 * A Moment counts as gone when no stored photo of its second is left, or its second is tombstoned (`MomentDao.photoMomentsOf`): the rebuildable projection row is NOT the test (a forgotten fact only drops
 * and rebuilds it). Log lines are literals plus a count: never an id, name, note or position.
 */
/** The pins a Forget would delete, for its confirmation: how many, and the first [NAMES_SHOWN] names as the pin list shows them. UI only: a name never goes into a log. */
data class PinsGoing(val count: Int, val names: List<String>) {
    companion object { const val NAMES_SHOWN = 5; val NONE = PinsGoing(0, emptyList()) }
}

object PinMomentLinks {
    /** Runs [block] (an explicit Forget, inside the DataOpGate) with the cascade around it. [reached] limits the cascade to Moments the Forget really targets (a time range); default every gone Moment. */
    suspend fun <T> explicit(context: Context, reached: (String) -> Boolean = { true }, block: suspend () -> T): T {
        runCatching { armBefore(context, all = false) }
        try { return block() } finally { runCatching { cascadeAfter(context, reached) } }
    }

    /** Arms the pins that reference a live Moment ([all] = every linked pin, for Delete all). Under the pin lock. */
    suspend fun armBefore(context: Context, all: Boolean) {
        PlacePinning.underPinLock {
            armStore(PlaceGraph.pins(context), all) { ids -> MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet() }
        }
    }

    internal suspend fun armStore(store: UserPinStore, all: Boolean, alive: suspend (List<String>) -> Set<String>) {
        val linked = store.pins().filter { it.momentId != null }
        if (store.corrupt || linked.isEmpty()) return
        val live = if (all) emptySet() else alive(linked.mapNotNull { it.momentId }.distinct())
        store.arm(linked.filter { all || it.momentId in live }.map { it.id }.toSet())
    }

    /** After an explicit Forget committed (or at start, from a crash): deletes the armed pins whose Moment is gone (the pack's copy too), clears every other dead link. Returns the pins deleted (null = not done, stays armed). */
    suspend fun cascadeAfter(context: Context, reached: (String) -> Boolean = { true }): Int? = PlacePinning.underPinLock {
        cascadeStore(PlaceGraph.pins(context), { ids -> MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet() }, { PlaceGraph.savePins(context, it) }, reached)
    }

    internal suspend fun cascadeStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, reached: (String) -> Boolean = { true }): Int? {
        val armed = store.armed()
        val pins = store.pins()
        if (store.corrupt) return null // an unreadable list is never rewritten; the arming stays
        var deleted = 0
        if (armed.isNotEmpty()) {
            val cand = pins.filter { it.id in armed && it.momentId != null }
            val live = if (cand.isEmpty()) emptySet() else alive(cand.mapNotNull { it.momentId }.distinct())
            val dead = cand.filter { it.momentId !in live && reached(it.momentId!!) }.map { it.id }.toSet()
            if (dead.isNotEmpty() && !save(pins.filter { it.id !in dead })) return null
            deleted = dead.size
            store.disarm()
            if (deleted > 0) PcLog.i("PcPlace", "pin deleted with its forgotten moment count=$deleted")
        }
        sweepStore(store, alive) ?: return null
        return deleted
    }

    /**
     * For the confirmation of an explicit Forget: how many pins would be deleted with it. [reach] answers which of the linked LIVE Moment ids the Forget leaves without a photo (null = every one: Delete all).
     * Read only. Never throws (0 when unreadable).
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

    /** Non-explicit removal: clears every link whose Moment is gone (the pins stay). Returns how many pins lost their link (null = unreadable or not rewritten). Skipped while a cascade is armed. */
    suspend fun sweep(context: Context): Int? = PlacePinning.underPinLock {
        val store = PlaceGraph.pins(context)
        if (store.armed().isNotEmpty()) 0
        else sweepStore(store) { ids -> MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet() }
    }

    internal suspend fun sweepStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>): Int? {
        val linked = store.pins().mapNotNull { it.momentId }.toSet()
        if (store.corrupt) return null
        if (linked.isEmpty()) return 0
        val cleared = store.clearMomentsExcept(alive(linked.toList())) ?: return null
        val n = cleared.size
        if (n > 0) PcLog.i("PcPlace", "pin moment link cleared by removal count=$n")
        return n
    }

    /** Registered once at start (ContextApp). Never throws: a failed sweep is repeated by the next removal, the next screen visit and the next start. */
    fun listener(context: Context): ForgetListener {
        val app = context.applicationContext
        return listenerOf({ sweep(app) }, { armBefore(app, all = true); cascadeAfter(app) })
    }

    internal fun listenerOf(sweep: suspend () -> Unit, deleteAll: suspend () -> Unit): ForgetListener = object : ForgetListener {
        override suspend fun onFramesForgotten(frameIds: List<String>) { safely(sweep) }
        override suspend fun onRangeForgotten(fromMs: Long, toMs: Long, traceIds: List<String>) { safely(sweep) }
        override suspend fun onUserForget() { safely(sweep) }
        override suspend fun onDeleteAll() { safely(deleteAll) }
    }

    private suspend fun safely(f: suspend () -> Unit) { try { f() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w("PcPlace", "pin moment sweep failed") } }
}
