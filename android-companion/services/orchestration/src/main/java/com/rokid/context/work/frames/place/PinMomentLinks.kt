package com.rokid.context.work.frames.place

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPinStore.Arming
import com.rokid.context.memory.place.UserPlace
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The pins a Forget would delete, for its confirmation: how many, and the first [NAMES_SHOWN] names as the pin list shows them. UI only: a name never goes into a log. */
data class PinsGoing(val count: Int, val names: List<String>, /** The ids of exactly these pins: a confirmation is bound to this set, not to a count. */ val ids: Set<String> = emptySet(), /** True when the lookup itself failed: "no pins" is then UNKNOWN, not none, and a confirmation must not proceed on it. */ val failed: Boolean = false) {
    companion object { const val NAMES_SHOWN = 5; val NONE = PinsGoing(0, emptyList()); val FAILED = PinsGoing(0, emptyList(), emptySet(), true) }
}

/** The pin removal of an explicit Forget could not be prepared (the durable arming was not stored or not readable back): the Forget is NOT run. */
class PinArmFailed : Exception("pin removal could not be prepared")

/** The Forget committed but its pin removal could not be durably recorded (the committed dead set was not stored): the pins stay, with their link cleared by the next sweep. */
class PinCommitNotStored : Exception("pin removal not recorded")

/**
 * Slice 1.4.2 "Pin to Moment": what becomes of a pin whose reference Moment goes away (the link is an 8-hex Moment id on the pin, prefs `place_pins`). Two kinds of removal, two fates:
 *
 *  - an EXPLICIT user Forget (a photo, a Moment, a Scene, a time range, a multi-select) or Delete all (also its crash resume): the pin is FORGOTTEN TOO. The operation stores a durable ARMING that belongs to
 *    that ONE operation and names ONLY the (pin, Moment) pairs whose Moment is in the operation's TARGET set (the same set its confirmation showed; Delete all: every pair alive when it starts). When the Forget
 *    (or the wipe epoch) really committed, ONE write stores `committed = true` together with exactly the DEAD set (the armed pairs whose Moment is now gone). Only that stored set is ever deleted, by the
 *    operation itself or by [settle] after a crash; "every linked pin" is never used, and a Moment that goes later for another reason is not in it.
 *  - every other removal (retention expiry, the suspect-photo sweep, the invalid-Moment purge, ...): the pin is NEVER deleted; only its LINK is cleared and the pin shows "Needs a Moment".
 *
 *  - the UNDO of a mark (glasses voice or tap, or the phone's "Forget this mark", `ForgetActions.forgetMomentKeepPinsOutcome`): no dialog names pins, so it is not an explicit pin Forget. It runs the photo Forget with an
 *    EMPTY target (nothing armed); a pin on that Moment keeps its place and loses only its link ("Needs a Moment").
 *
 * While an explicit operation that HOLDS AN ARMING runs in this process the sweeps and [settle] leave everything alone (checked INSIDE the pin lock; the operation's own settle is exempt). An explicit operation whose
 * target holds no pin pairs is in flight but arms nothing, so it does NOT block the sweeps or settle (there is no arming of it to protect); `busy` looks only at armed operations.
 * A replacement arming that cannot be stored puts the OLDER arming back (never drops it); a resume whose promoted arming cannot be stored keeps the older one and the wipe keeps its pending marker.
 * A new arming MERGES an older unfinished committed one (its dead set is carried), it never overwrites it.
 *
 * INVARIANT: every stored arming has a resolution at the next start. A committed arming (any kind) is finished from its dead set and removed; an uncommitted forget arming is dropped (its carried dead set
 * restored); an uncommitted WIPE arming is kept only while the epoch store says a wipe is pending (the resume then promotes it) and is dropped otherwise, so no arming, and no protection of any pin's link,
 * can outlive the operation that made it. Tested as a table over kind x committed x wipePending.
 *
 * Crash points and what the next start does:
 * | crash / failure point                                              | stored state                  | next start / result                                                          |
 * |--------------------------------------------------------------------|-------------------------------|------------------------------------------------------------------------------|
 * | mark undo: any point (it arms nothing)                             | none                          | the pin stays; the sweep clears its link; never a pin deleted                |
 * | before the arming is stored (or it cannot be stored)               | none                          | the Forget does not run (PinArmFailed): nothing forgotten                    |
 * | after arming, before the Forget commits (or it fails / throws)     | uncommitted forget arming     | dropped (never acted on); a carried committed dead set is restored           |
 * | Forget committed, before the commit write (or the write fails)     | uncommitted forget arming     | dropped: the pins stay, dead links are cleared by the sweep (no pin deleted) |
 * | commit write done, before the pins are deleted                     | committed + dead set          | settle deletes exactly the dead set, then disarms                            |
 * | pins deleted, before disarm                                        | committed + dead set          | settle finds nothing to delete, disarms                                      |
 * | list unreadable, save refused, store throws after the commit       | committed + dead set kept     | retried at every start; the Forget is reported done with a pending notice    |
 * | Delete all: before the epoch commit                                | uncommitted wipe arming       | abortWipe drops it; after a crash the next sweep/settle/Delete all drops it (no wipe pending); the pins stay |
 * | Delete all: epoch committed, before the commit write (or it fails) | uncommitted wipe arming       | the resume promotes it (dead = its armed pairs); the leftover keeps the resume |
 * | Delete all: after the commit write                                 | committed + dead set          | the resume keeps it and arms nothing new; settle deletes the dead set        |
 * | a user Forget (with pairs) while that uncommitted wipe arming is pending | uncommitted wipe arming | the Forget is refused (PinArmFailed, "try again"): the arming is never replaced; a Forget with no pairs is unaffected |
 *
 * A Moment counts as gone when no stored photo of its second is left, or its second is tombstoned (`MomentDao.photoMomentsOf`): the rebuildable projection row is NOT the test. Log lines are literals plus
 * a count: never an id, name, note or position.
 */
object PinMomentLinks {
    /** Explicit operations (a Forget, a wipe) running in this process, by op id. */
    private val inflight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** The subset of [inflight] that has a stored arming right now (set inside the pin lock right after a successful arm, so a sweep, which also takes the lock, sees it atomically). */
    private val armed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** True when ANOTHER operation holds an arming: only then is its arming at risk of being overwritten or acted on. An operation in flight that armed nothing (no pins in its target) does not block anyone. */
    private fun busy(self: String?): Boolean = armed.any { it != self }

    private fun momentsOf(context: Context): suspend (List<String>) -> Set<String> = { ids -> MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet() }

    // ---- explicit Forget ----

    /**
     * Runs [block] (an explicit Forget, inside the DataOpGate) with the cascade around it. [target] says which of the live linked Moment ids this Forget removes (what its confirmation showed); only pairs of those
     * are armed. [committed] says whether the Forget's result means it committed. Throws [PinArmFailed] BEFORE running [block] when pins would be armed and the arming cannot be stored. A failure of the cascade
     * AFTER the Forget committed never fails it: [onPending] is called and the cleanup finishes at the next start.
     */
    suspend fun <T> explicit(context: Context, target: suspend (List<String>) -> Set<String>, committed: (T) -> Boolean = { true }, onPending: () -> Unit = {}, onNotRemoved: () -> Unit = {}, allowedPins: Set<String>? = null, block: suspend () -> T): T =
        explicitStore(PlaceGraph.pins(context), momentsOf(context), { PlaceGraph.savePins(context, it) }, target, committed, onPending, block, onNotRemoved, allowedPins, wipePendingOf(context))

    internal suspend fun <T> explicitStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, target: suspend (List<String>) -> Set<String>, committed: (T) -> Boolean, onPending: () -> Unit = {}, block: suspend () -> T, onNotRemoved: () -> Unit = {}, allowedPins: Set<String>? = null, wipePending: () -> Boolean = { false }): T {
        val op = UUID.randomUUID().toString()
        inflight.add(op)
        try {
            PlacePinning.underPinLock {
                resolveStore(store, alive, save, op, wipePending) // an earlier operation's leftovers are finished (committed) or dropped (not)
                val pairs = targetPairs(store, alive, target).let { p -> if (allowedPins == null) p else p.filterKeys { it in allowedPins } } // inside the pin lock: only the pins the user confirmed
                if (pairs.isNotEmpty() && busy(op)) throw PinArmFailed() // another explicit operation is armed right now: never overwrite its arming (nothing is forgotten; try again)
                if (pairs.isNotEmpty() && store.arming()?.let { it.kind == "wipe" && !it.committed } == true && wipePending()) throw PinArmFailed() // an earlier Delete all committed its epoch but its commit write is still owed: its arming (the pins it promised to delete) is never replaced; nothing is forgotten, try again after the resume
                if (pairs.isNotEmpty()) { val prev = store.arming(); if (!store.arm(Arming(op, "forget", false, pairs, carried(store, wipePending, carryWipe = false)))) { restoreArming(store, prev); throw PinArmFailed() }; armed.add(op) }
            }
            val r = try { block() } catch (t: Throwable) { PlacePinning.underPinLock { dropIfOp(store, op) }; throw t }
            try {
                PlacePinning.underPinLock {
                    if (committed(r)) commitOp(store, op, alive, save) else dropIfOp(store, op)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: PinCommitNotStored) {
                PcLog.w("PcPlace", "pin removal could not be recorded: the pins stay")
                onNotRemoved()
            } catch (e: Exception) {
                PcLog.w("PcPlace", "pin cleanup after forget will finish at next start")
                onPending()
            }
            return r
        } finally { inflight.remove(op); armed.remove(op) }
    }

    /**
     * An older unfinished arming's pairs to be carried by a new arming (merge, never overwrite): a committed one's dead set; and, ONLY for a fresh Delete all ([carryWipe]), an uncommitted WIPE arming's dead + pairs
     * while its epoch is still pending (the wipe did commit, only its arming write did not). "A wipe is pending" can be true because of any earlier wipe, so a user Forget never inherits those pairs: they would be
     * deleted without its allowed-pins filter.
     */
    private fun carried(store: UserPinStore, wipePending: () -> Boolean, carryWipe: Boolean): Map<String, String> {
        val a = store.arming() ?: return emptyMap()
        return when { a.committed -> a.dead; carryWipe && a.kind == "wipe" && wipePending() -> a.dead + a.pairs; else -> emptyMap() }
    }

    private suspend fun targetPairs(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, target: suspend (List<String>) -> Set<String>): Map<String, String> {
        val live = livePairs(store, alive)
        if (live.isEmpty()) return emptyMap()
        val hit = try { target(live.values.distinct()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptySet() } // an unplannable target arms nothing: pins keep, links are cleared
        return live.filter { it.value in hit }
    }

    private suspend fun livePairs(store: UserPinStore, alive: suspend (List<String>) -> Set<String>): Map<String, String> {
        val linked = store.pins().filter { it.momentId != null }
        if (store.corrupt || linked.isEmpty()) return emptyMap()
        val live = alive(linked.mapNotNull { it.momentId }.distinct())
        return linked.filter { it.momentId in live }.associate { it.id to it.momentId!! }
    }

    /**
     * A replacement arming could not be stored. SharedPreferences.commit() updates its in-memory map even when the disk write fails, so the failed write may look stored. The OLDER arming ([prev], read before the
     * write) is put back, never dropped: it can be a committed dead set (pin deletion still owed) or an uncommitted wipe arming whose resume is pending. Only when there was none is the failed record removed.
     */
    private fun restoreArming(store: UserPinStore, prev: Arming?) {
        if (prev == null) { store.disarm(); return }
        if (!store.arm(prev)) PcLog.w("PcPlace", "older pin arming not restored")
    }

    /** The operation did not commit: its arming goes; a carried committed dead set is restored (never lost). */
    private fun dropIfOp(store: UserPinStore, op: String) {
        val a = store.arming() ?: return
        if (a.op != op) return
        if (a.dead.isNotEmpty()) { if (!store.arm(Arming(a.op, a.kind, true, emptyMap(), a.dead))) PcLog.w("PcPlace", "pin arming not restored") } else store.disarm()
    }

    /** The Forget committed: ONE write stores `committed` with the dead set (armed pairs whose Moment is gone), then those pins are deleted. A failed write leaves the uncommitted arming (dropped later, pins kept). */
    private suspend fun commitOp(store: UserPinStore, op: String, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean) {
        val a = store.arming()?.takeIf { it.op == op } ?: return
        val live = if (a.pairs.isEmpty()) emptySet() else alive(a.pairs.values.distinct())
        val dead = a.dead + a.pairs.filter { it.value !in live }
        if (!store.arm(Arming(op, a.kind, true, emptyMap(), dead)) && !store.arm(Arming(op, a.kind, true, emptyMap(), dead))) {
            // SharedPreferences.commit() updates its in-memory map even when the disk write fails: put the pre-commit state back, so a sweep in this process cannot act on a "committed" arming the user was told does not exist
            if (a.dead.isNotEmpty()) store.arm(Arming(a.op, a.kind, true, emptyMap(), a.dead)) else store.disarm()
            throw PinCommitNotStored() // one retry; if it still fails the pins stay
        }
        finishCommitted(store, dead, alive, save)
    }

    private suspend fun finishCommitted(store: UserPinStore, dead: Map<String, String>, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean): Int {
        val n = deleteDead(store, dead, alive, save) ?: throw IllegalStateException("pins not rewritten")
        if (!store.disarm()) throw IllegalStateException("arming not removed")
        if (n > 0) PcLog.i("PcPlace", "pin deleted with its forgotten moment count=$n")
        sweepStore(store, alive)
        return n
    }

    /** Deletes the pins of [dead] (same pin, same Moment) whose Moment is still gone. Null = unreadable list or not saved. */
    private suspend fun deleteDead(store: UserPinStore, dead: Map<String, String>, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean): Int? {
        if (dead.isEmpty()) return 0
        val pins = store.pins()
        if (store.corrupt) return null
        val cand = pins.filter { dead[it.id] != null && it.momentId == dead[it.id] }
        if (cand.isEmpty()) return 0
        val live = alive(cand.mapNotNull { it.momentId }.distinct())
        val gone = cand.filter { it.momentId !in live }.map { it.id }.toSet()
        if (gone.isNotEmpty() && !save(pins.filter { it.id !in gone })) return null
        if (gone.isNotEmpty()) ShadowCaches.clearAll() // a pin went with its Moment: the shadow's in-memory vectors go at once
        return gone.size
    }

    /**
     * Start-up and sweeps: a COMMITTED arming is finished from its stored dead set only. An uncommitted forget arming is dropped (its carried dead set restored); an uncommitted WIPE arming is kept for the wipe's
     * resume to promote. Skipped while another explicit operation runs ([self] is exempt: the operation's own call). Returns the pins deleted.
     */
    internal suspend fun resolveStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, self: String? = null, wipePending: () -> Boolean = { false }): Int {
        if (busy(self)) return 0
        val a = store.arming()
        if (a == null) { if (store.hasArmingRecord()) store.disarm(); return 0 }
        if (!a.committed) {
            if (a.op == self) return 0
            if (a.kind == "wipe" && wipePending()) return 0 // the wipe's epoch committed: its resume promotes this arming
            if (a.dead.isEmpty()) { store.disarm(); return 0 }
            if (!store.arm(Arming(a.op, a.kind, true, emptyMap(), a.dead))) return 0
            return finishQuietly(store, a.dead, alive, save)
        }
        return finishQuietly(store, a.dead, alive, save)
    }

    private suspend fun finishQuietly(store: UserPinStore, dead: Map<String, String>, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean): Int =
        try { finishCommitted(store, dead, alive, save) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { 0 } // stays armed: retried at the next start

    // ---- Delete all ----

    /**
     * Arms every pin that references a live Moment (or, with [confirmedPins], only those the confirmation named) for the wipe about to start. A RESUME arms nothing new (a pin created after the crash is never armed): it keeps the earlier committed dead set, or promotes the
     * earlier uncommitted wipe arming (the epoch did commit). Throws [PinArmFailed] before anything is deleted. Pair with [endWipe].
     */
    suspend fun beginWipe(context: Context, resume: Boolean, confirmedPins: Set<String>? = null): String = beginWipeStore(PlaceGraph.pins(context), momentsOf(context), { PlaceGraph.savePins(context, it) }, resume, confirmedPins, wipePendingOf(context))

    private fun wipePendingOf(context: Context): () -> Boolean = { com.rokid.context.memory.db.DeletionEpochStore.of(context).wipePending() }

    internal suspend fun beginWipeStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, resume: Boolean, confirmedPins: Set<String>? = null, wipePending: () -> Boolean = { false }): String {
        val op = UUID.randomUUID().toString()
        inflight.add(op)
        try {
            PlacePinning.underPinLock {
                val old = store.arming()
                if (resume) {
                    val dead = when { old == null -> emptyMap(); old.committed -> old.dead; old.kind == "wipe" && wipePending() -> old.dead + old.pairs; else -> old.dead } // promoted only when the epoch says the wipe committed
                    if (dead.isNotEmpty()) {
                        if (store.arm(Arming(op, "wipe", true, emptyMap(), dead))) armed.add(op)
                        else { restoreArming(store, old); resumeNotPromoted.add(op); PcLog.w("PcPlace", "pin arming not kept on resume") } // the arming must not look committed in memory; the wipe keeps its pending marker (see [resumePromoted])
                    }
                } else {
                    resolveStore(store, alive, save, op, wipePending) // a stale uncommitted wipe arming of an earlier run that never got to its epoch commit is dropped here
                    val pairs = livePairs(store, alive).let { p -> if (confirmedPins == null) p else p.filterKeys { it in confirmedPins } } // only the pins the Delete all dialog named ([confirmedPins]; null = every live linked pin, for non-UI callers)
                    if (pairs.isNotEmpty() && busy(op)) throw PinArmFailed() // another explicit operation is armed right now: never overwrite its arming
                    if (pairs.isNotEmpty()) { val prev = store.arming(); if (!store.arm(Arming(op, "wipe", false, pairs, carried(store, wipePending, carryWipe = true)))) { restoreArming(store, prev); throw PinArmFailed() }; armed.add(op) }
                }
            }
        } catch (t: Throwable) { inflight.remove(op); armed.remove(op); throw t }
        return op
    }

    /** The wipe's epoch committed: ONE write marks its arming committed with its dead set (every armed pair: all Moments are being wiped). False = not stored: the resume promotes the uncommitted arming. */
    suspend fun wipeCommitted(context: Context, op: String): Boolean = PlacePinning.underPinLock { wipeCommittedStore(PlaceGraph.pins(context), op) }

    internal fun wipeCommittedStore(store: UserPinStore, op: String): Boolean {
        val a = store.arming() ?: return true
        if (a.op != op) return false
        return store.arm(Arming(op, a.kind, true, emptyMap(), a.dead + a.pairs))
    }

    /** The epoch could NOT be committed: nothing was deleted, so the arming is dropped (the pins stay). */
    suspend fun abortWipe(context: Context, op: String) { PlacePinning.underPinLock { dropIfOp(PlaceGraph.pins(context), op) } }

    fun endWipe(op: String) { inflight.remove(op); armed.remove(op); resumeNotPromoted.remove(op) }

    /** Wipe operations whose RESUME could not store the promoted (committed) arming: the older arming was put back and its pin deletion is still owed. */
    private val resumeNotPromoted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** False when the resume of wipe [op] could not store its promoted arming: the wipe must keep its durable pending marker so the next start retries the pin deletion. */
    fun resumePromoted(op: String): Boolean = op !in resumeNotPromoted

    // ---- settling, sweeps ----

    /** Finishes a committed pending arming (a crash between a Forget's commit and its cascade, an interrupted wipe) and drops one that never committed; then clears dead links. [op] = the wipe's own call (exempt from the busy check). Null = list unreadable or not rewritten. */
    suspend fun settle(context: Context, op: String? = null): Int? = PlacePinning.underPinLock {
        if (busy(op)) return@underPinLock 0
        val store = PlaceGraph.pins(context)
        val deleted = resolveStore(store, momentsOf(context), { PlaceGraph.savePins(context, it) }, op, wipePendingOf(context))
        sweepStore(store, momentsOf(context)) ?: return@underPinLock null
        if (armingOwed(store, wipePendingOf(context))) return@underPinLock null // pin deletion is still owed (a committed arming that could not be finished, or the wipe's own unpromoted one): not done
        deleted
    }

    /** True while a stored arming still owes pin deletion: any committed one, or the pending wipe's uncommitted one. */
    private fun armingOwed(store: UserPinStore, wipePending: () -> Boolean): Boolean {
        val a = store.arming() ?: return false
        return a.committed || (a.kind == "wipe" && wipePending())
    }

    /** Non-explicit removal: settles any leftover arming (so it never blocks), then clears every link whose Moment is gone (the pins stay). Skipped while an explicit operation runs in this process. */
    suspend fun sweep(context: Context): Int? = sweepGuarded(PlaceGraph.pins(context), momentsOf(context), { PlaceGraph.savePins(context, it) }, wipePendingOf(context))

    internal suspend fun sweepGuarded(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, wipePending: () -> Boolean = { false }): Int? =
        PlacePinning.underPinLock { if (busy(null)) 0 else sweepAll(store, alive, save, wipePending) }

    internal suspend fun sweepAll(store: UserPinStore, alive: suspend (List<String>) -> Set<String>, save: suspend (List<UserPlace>) -> Boolean, wipePending: () -> Boolean = { false }): Int? {
        resolveStore(store, alive, save, null, wipePending)
        return sweepStore(store, alive)
    }

    internal fun abortWipeStore(store: UserPinStore, op: String) = dropIfOp(store, op)

    internal suspend fun sweepStore(store: UserPinStore, alive: suspend (List<String>) -> Set<String>): Int? {
        val linked = store.pins().mapNotNull { it.momentId }.toSet()
        if (store.corrupt) return null
        if (linked.isEmpty()) return 0
        val armed = store.arming()?.let { it.pairs.keys + it.dead.keys } ?: emptySet() // an unfinished arming (a wipe not yet committed, a dead set not yet deleted) still needs these pins' links: never cleared here
        val cleared = store.clearMomentsExcept(alive(linked.toList()), armed) ?: return null
        val n = cleared.size
        if (n > 0) { ShadowCaches.clearAll(); PcLog.i("PcPlace", "pin moment link cleared by removal count=$n") }
        return n
    }

    // ---- confirmation ----

    /**
     * For the confirmation of an explicit Forget: how many pins would be deleted with it. [reach] answers which of the linked LIVE Moment ids the Forget leaves without a photo (null = every one: Delete all).
     * Read only. Never throws ([PinsGoing.FAILED] when the lookup failed: unknown, not none).
     */
    suspend fun pinsGoing(context: Context, reach: (suspend (List<String>) -> Set<String>)? = null): PinsGoing = try {
        val pins = PlaceGraph.pins(context).pins().filter { it.momentId != null }
        if (pins.isEmpty()) PinsGoing.NONE
        else {
            val ids = pins.mapNotNull { it.momentId }.distinct()
            val live = MemoryGraph.get(context).db.momentDao().photoMomentsOf(ids).map { it.momentId }.toSet()
            val going = if (reach == null) ids.toSet() else reach(live.toList())
            pins.filter { it.momentId in going }.let { g -> PinsGoing(g.size, g.take(PinsGoing.NAMES_SHOWN).map { it.name }, g.map { it.id }.toSet()) }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PinsGoing.FAILED }

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
