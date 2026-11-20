package com.rokid.context.work.frames.place

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.forget.MomentForgetFixture
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPinStore.Arming
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.orchestration.forget.ForgetActions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Slice 1.4.2 "Pin to Moment", privacy side. An EXPLICIT user Forget of a pin's reference Moment (a photo, a range, Delete all, also its crash resume) deletes the pin with it, but ONLY the pairs of the operation's
 * TARGET set, stored as an exact DEAD set in one write with `committed`. Every NON-explicit removal (retention expiry, a sweep) never deletes a pin, it only clears the link; a stale or uncommitted arming is
 * never acted on. Real Forget code on an in-memory database drives the real cascade and sweep.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PinMomentLinksTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val fx = MomentForgetFixture(db)
    private val rawPrefs get() = ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE)
    private val store get() = UserPinStore(rawPrefs)
    private val sec2 = MomentForgetFixture.SEC + 100
    private val id1 = fx.id
    private val id2 = MomentId.format(sec2)
    private val ids = (0..3).map { "user:00000000-0000-0000-0000-00000000000$it" }
    private fun pin(i: Int, moment: String?) = UserPlace(ids[i], "Pin $i", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = moment)

    private val alive: suspend (List<String>) -> Set<String> = { l -> db.momentDao().photoMomentsOf(l).map { it.momentId }.toSet() }
    private val save: suspend (List<UserPlace>) -> Boolean = { l -> store.write(l) }
    private val noSave: suspend (List<UserPlace>) -> Boolean = { false }
    private val all: suspend (List<String>) -> Set<String> = { it.toSet() }
    private val only1: suspend (List<String>) -> Set<String> = { l -> l.filter { it == id1 }.toSet() }
    private val none: suspend (List<String>) -> Set<String> = { emptySet() }

    private suspend fun <T> explicit(st: UserPinStore = store, sv: suspend (List<UserPlace>) -> Boolean = save, target: suspend (List<String>) -> Set<String> = all, committed: (T) -> Boolean = { true }, onPending: () -> Unit = {}, onNotRemoved: () -> Unit = {}, allowed: Set<String>? = null, wipePending: () -> Boolean = { false }, forget: suspend () -> T): T =
        PinMomentLinks.explicitStore(st, alive, sv, target, committed, onPending, forget, onNotRemoved, allowed, wipePending)
    /** What the epoch store says: true once a Delete all committed its epoch and has not finished. */
    private var pendingFlag = false
    private suspend fun sweep() = PinMomentLinks.sweepGuarded(store, alive, save) { pendingFlag }
    private suspend fun resolve(self: String? = null) = PlacePinning.underPinLock { PinMomentLinks.resolveStore(store, alive, save, self) { pendingFlag } }

    @Before fun setUp() = runBlocking<Unit> {
        rawPrefs.edit().clear().commit()
        ForgetListeners.clearForTests()
        ForgetListeners.register(PinMomentLinks.listenerOf({ sweep() }, { resolve() })) // the production listener: it leaves the pins alone while an explicit operation runs
        fx.frame("a", MomentForgetFixture.MS + 5); fx.project(id1, MomentPartKind.FRAME to "a")
        fx.frame("b", sec2 * 1000 + 5); fx.project(id2, MomentPartKind.FRAME to "b")
        assertTrue(store.write(listOf(pin(0, id1), pin(1, id2), pin(2, null))))
    }
    @After fun tearDown() { ForgetListeners.clearForTests(); db.close(); rawPrefs.edit().clear().commit() }

    private fun names() = store.pins().map { it.name }
    private fun links() = store.pins().map { it.momentId }
    private val pairs get() = mapOf(ids[0] to id1, ids[1] to id2)

    // ---- explicit Forget: the pin goes with its Moment, only the target set ----

    @Test fun anExplicitForgetOfThePhotoDeletesThePinsOfThatMomentOnly() = runBlocking<Unit> {
        explicit(target = only1) { fx.service.forgetMoment("a") }
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertEquals(listOf(id2, null), links())
        assertNull("armed state is gone", store.arming())
    }

    /** Ruling O6: the undo of a mark runs the photo Forget with an EMPTY target (`ForgetActions.forgetMomentKeepPinsOutcome`): the pin on that Moment survives with its link cleared. */
    @Test fun aMarkUndoForgetOfThePhotoKeepsThePinAndClearsItsLink() = runBlocking<Unit> {
        explicit(target = none) { fx.service.forgetMoment("a") }
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(listOf(null, id2, null), links())
        assertNull("nothing was armed", store.arming())
    }

    @Test fun aRangeForgetDeletesOnlyThePinsOfItsTargetSet() = runBlocking<Unit> {
        explicit(target = { l -> l.filter { MomentId.parse(it) == sec2 }.toSet() }) { fx.forget.forgetRange(sec2 * 1000, sec2 * 1000 + 999) }
        assertEquals(listOf("Pin 0", "Pin 2"), names())
    }

    @Test fun aMomentOutsideTheTargetThatGoesForAnotherReasonDuringTheOperationKeepsItsPin() = runBlocking<Unit> {
        explicit(target = only1) { fx.service.forgetMoment("a"); fx.service.expirePhotos(listOf("b")) } // b expires (retention) while the operation runs
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull("link cleared, pin kept", store.pins().first().momentId)
    }

    @Test fun aFactForgetArmsNothingAndNeverDeletesAPin() = runBlocking<Unit> {
        fx.memory("m1", "a")
        explicit(target = none) { fx.service.forgetFact("m1") }
        assertNull(store.arming()); assertEquals(listOf(id1, id2, null), links())
    }

    @Test fun aForgetThatDidNotCommitKeepsThePinsAndDropsTheArming() = runBlocking<Unit> {
        explicit(committed = { _: Boolean -> false }) { true }
        assertEquals(3, store.pins().size); assertNull(store.arming())
        try { explicit<Unit> { error("boom") } } catch (e: IllegalStateException) { }
        assertEquals(3, store.pins().size); assertNull(store.arming())
    }

    // ---- P2-A: only the stored dead set is ever deleted ----

    @Test fun aLeftoverCommittedArmingDeletesOnlyItsDeadSetNotAMomentThatWentLaterForAnotherReason() = runBlocking<Unit> {
        ForgetListeners.clearForTests()
        assertTrue(store.arm(Arming("old", "forget", true, emptyMap(), mapOf(ids[0] to id1))))
        fx.service.forgetMoment("a") // the Forget committed, the cascade did not run (crash)
        fx.service.expirePhotos(listOf("b")) // later, retention takes pin 1's Moment
        assertEquals(1, resolve())
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull(store.arming())
    }

    @Test fun aRangeResumeKeepsItsExactSetEvenThoughNoRangeFilterIsLeft() = runBlocking<Unit> {
        // the range armed only pin 0 and committed with dead {pin 0}; pin 1's Moment is also gone now (retention): the resume must not take it
        ForgetListeners.clearForTests()
        assertTrue(store.arm(Arming("rng", "forget", true, emptyMap(), mapOf(ids[0] to id1))))
        fx.service.forgetMoment("a"); fx.service.forgetMoment("b")
        resolve()
        assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    @Test fun anArmingThatNeverCommittedIsDroppedNotCarriedIntoTheNextForget() = runBlocking<Unit> {
        ForgetListeners.clearForTests()
        assertTrue(store.arm(Arming("never", "forget", false, pairs)))
        fx.service.forgetMoment("a") // a Forget by another path; the old arming never committed
        explicit(target = none) { fx.service.expirePhotos(listOf("b")) }
        assertEquals(3, store.pins().size); assertNull(store.arming())
    }

    // ---- P3: merge, restore, post-commit failure ----

    @Test fun aNewArmingMergesAnOlderUnfinishedCommittedOneAndAFailedOperationRestoresIt() = runBlocking<Unit> {
        ForgetListeners.clearForTests()
        assertTrue(store.arm(Arming("old", "forget", true, emptyMap(), mapOf(ids[0] to id1))))
        fx.service.forgetMoment("a")
        // a second operation cannot finish the old one (the list cannot be saved) and does not commit
        explicit(sv = noSave, target = { l -> l.filter { it == id2 }.toSet() }, committed = { _: Boolean -> false }) {
            assertEquals("merged: the old dead set rides on the new arming", mapOf(ids[0] to id1), store.arming()!!.dead); true
        }
        val a = store.arming()!!
        assertTrue(a.committed); assertEquals(mapOf(ids[0] to id1), a.dead); assertTrue(a.pairs.isEmpty())
        resolve()
        assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    @Test fun anExceptionAfterTheForgetCommittedReportsPendingNotFailureAndTheNextStartFinishesIt() = runBlocking<Unit> {
        var pending = false
        val r = explicit(sv = noSave, target = only1, onPending = { pending = true }) { fx.service.forgetMoment("a"); "done" }
        assertEquals("done", r); assertTrue(pending)
        assertEquals("pins still there", 3, store.pins().size)
        assertTrue(store.arming()!!.committed); assertEquals(mapOf(ids[0] to id1), store.arming()!!.dead)
        resolve()
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull(store.arming())
    }

    // ---- Delete all ----

    @Test fun deleteAllDeletesEveryPinThatHadALiveMomentAndKeepsTheRest() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        assertTrue(PinMomentLinks.wipeCommittedStore(store, op)); db.wipeDao().wipeMemory()
        assertEquals("the operation's own settle is exempt", 2, resolve(self = op)); PinMomentLinks.endWipe(op)
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aCrashedDeleteAllResumesAndDeletesOnlyTheCommittedSetNotAPinCreatedAfterTheCrash() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }); PinMomentLinks.wipeCommittedStore(store, op); db.wipeDao().wipeMemory() // crash before the cascade
        PinMomentLinks.endWipe(op)
        fx.frame("c", (sec2 + 50) * 1000 + 5); fx.project(MomentId.format(sec2 + 50), MomentPartKind.FRAME to "c")
        assertTrue(store.write(store.pins() + pin(3, MomentId.format(sec2 + 50)))) // a pin made after the crash, on a live Moment
        pendingFlag = true
        val op2 = PinMomentLinks.beginWipeStore(store, alive, save, resume = true, wipePending = { pendingFlag })
        assertEquals("the resume arms nothing new", mapOf(ids[0] to id1, ids[1] to id2), store.arming()!!.dead)
        assertEquals(2, resolve(self = op2)); PinMomentLinks.endWipe(op2)
        assertEquals(listOf("Pin 2", "Pin 3"), names())
    }

    @Test fun deleteAllThatAbortsAtTheEpochCommitThenExpiryThenAnUnrelatedForgetNeverDeletesAPin() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        PinMomentLinks.abortWipeStore(store, op); PinMomentLinks.endWipe(op) // commitWipe threw: nothing was deleted
        assertNull(store.arming())
        fx.service.expirePhotos(listOf("b")) // retention: the listener sweep clears the link
        explicit(target = only1) { fx.service.forgetMoment("a") } // an unrelated explicit Forget of the other Moment
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull("pin 1 keeps no link but is NOT deleted", store.pins().first().momentId)
    }

    @Test fun ifTheWipeCommitWriteFailsTheResumePromotesTheUncommittedWipeArming() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        pendingFlag = true // the epoch committed
        val failing = UserPinStore(FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY))
        assertFalse("the commit write fails", PinMomentLinks.wipeCommittedStore(failing, op))
        db.wipeDao().wipeMemory(); PinMomentLinks.endWipe(op)
        assertFalse(store.arming()!!.committed)
        assertEquals("a start-up settle never drops an uncommitted wipe arming", 0, resolve())
        assertEquals(3, store.pins().size)
        sweep(); PlacePinning.underPinLock { PinMomentLinks.sweepStore(store, alive) } // the in-run settle and a start-up sweep: all Moments are gone, yet the armed pins keep their link
        assertEquals("the armed pins keep their links", listOf(id1, id2, null), links())
        pendingFlag = true
        val op2 = PinMomentLinks.beginWipeStore(store, alive, save, resume = true, wipePending = { pendingFlag })
        assertTrue(store.arming()!!.committed); assertEquals(2, resolve(self = op2)); PinMomentLinks.endWipe(op2)
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aCrashBetweenTheEpochCommitAndTheCommitWriteSurvivesAStartupSweepBeforeTheResume() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }) // epoch committed, process dies before wipeCommitted
        pendingFlag = true
        db.wipeDao().wipeMemory(); PinMomentLinks.endWipe(op)
        sweep(); resolve(); PlacePinning.underPinLock { PinMomentLinks.sweepStore(store, alive) } // ForgetStartup.settle, a listener, a screen visit
        assertEquals(listOf(id1, id2, null), links()); assertFalse(store.arming()!!.committed)
        pendingFlag = true
        val op2 = PinMomentLinks.beginWipeStore(store, alive, save, resume = true, wipePending = { pendingFlag })
        assertEquals(2, resolve(self = op2)); PinMomentLinks.endWipe(op2)
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aCommittedDeadSetThatCouldNotBeDeletedKeepsItsPinsLinkedThroughASweep() = runBlocking<Unit> {
        ForgetListeners.clearForTests()
        assertTrue(store.arm(Arming("old", "forget", true, emptyMap(), mapOf(ids[0] to id1))))
        fx.service.forgetMoment("a")
        assertEquals(0, PlacePinning.underPinLock { PinMomentLinks.resolveStore(store, alive, noSave) }) // not finished
        PlacePinning.underPinLock { PinMomentLinks.sweepStore(store, alive) }
        assertEquals("the dead pin still has its link, so the retry can find it", id1, store.pins().first().momentId)
        assertEquals(1, resolve()); assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    // ---- notices: only truthful ones ----

    private class FlakyPrefs(private val d: SharedPreferences, private var skip: Int, private var failures: Int) : SharedPreferences by d {
        override fun edit(): SharedPreferences.Editor {
            val e = d.edit()
            return object : SharedPreferences.Editor by e {
                var k: String? = null
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { k = key; e.putString(key, value); return this }
                override fun commit(): Boolean {
                    if (k != UserPinStore.ARMED_KEY) return e.commit()
                    if (skip > 0) { skip--; return e.commit() }
                    if (failures > 0) { failures--; e.apply(); return false } // like SharedPreferences: the in-memory map is updated even when the disk write fails
                    return e.commit()
                }
            }
        }
    }

    @Test fun ifTheCommittedDeadSetCannotBeStoredTheForgetIsNotRemovedNoticeNotPendingAndThePinsStay() = runBlocking<Unit> {
        var pending = false; var notRemoved = false
        val r = explicit(UserPinStore(FlakyPrefs(rawPrefs, skip = 1, failures = 2)), target = only1, onPending = { pending = true }, onNotRemoved = { notRemoved = true }) { fx.service.forgetMoment("a"); "done" }
        assertEquals("done", r); assertTrue(notRemoved); assertFalse("never the 'will be removed' promise", pending)
        assertEquals(3, store.pins().size); assertNull("the in-memory state matches the notice: nothing is armed", store.arming())
        sweep(); resolve() // a sweep in the same process, and the next start
        assertEquals("the pins stay, as the user was told", 3, store.pins().size); assertEquals(listOf(null, id2, null), links())
    }

    @Test fun aSingleFailedCommitWriteIsRetriedOnceAndTheForgetSucceedsQuietly() = runBlocking<Unit> {
        var pending = false; var notRemoved = false
        explicit(UserPinStore(FlakyPrefs(rawPrefs, skip = 1, failures = 1)), target = only1, onPending = { pending = true }, onNotRemoved = { notRemoved = true }) { fx.service.forgetMoment("a") }
        assertFalse(pending); assertFalse(notRemoved); assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    @Test fun thePinNoticeSurvivesACachedExportNoticeAndIsNeverInventedForAFailure() {
        val o = ForgetActions.Outcome.values().associateWith { it }
        assertEquals(ForgetActions.Outcome.CACHE_AND_PIN_PENDING, ForgetActions.withPinNotice(ForgetActions.Outcome.CACHE_CLEANUP_FAILED, pending = true, notRemoved = false))
        assertEquals(ForgetActions.Outcome.CACHE_AND_PIN_NOT_REMOVED, ForgetActions.withPinNotice(ForgetActions.Outcome.CACHE_CLEANUP_FAILED, pending = false, notRemoved = true))
        assertEquals(ForgetActions.Outcome.PIN_CLEANUP_PENDING, ForgetActions.withPinNotice(ForgetActions.Outcome.DONE, true, false))
        assertEquals(ForgetActions.Outcome.PIN_NOT_REMOVED, ForgetActions.withPinNotice(ForgetActions.Outcome.DONE, false, true))
        assertEquals(ForgetActions.Outcome.FAILED, ForgetActions.withPinNotice(ForgetActions.Outcome.FAILED, true, true))
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.withPinNotice(ForgetActions.Outcome.DONE, false, false))
        assertTrue(o.keys.filter { it.committed }.containsAll(listOf(ForgetActions.Outcome.PIN_NOT_REMOVED, ForgetActions.Outcome.CACHE_AND_PIN_PENDING)))
        assertFalse(ForgetActions.Outcome.PINS_NOT_CONFIRMED.committed); assertFalse(ForgetActions.Outcome.PIN_PREP_FAILED.committed)
    }

    // ---- stale wipe arming (P1 of round 4) ----

    @Test fun aCrashBeforeTheEpochCommitThenASweepDropsTheStaleWipeArmingAndClearsTheLinks() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }); PinMomentLinks.endWipe(op) // dies before commitWipe: no wipe pending
        fx.service.expirePhotos(listOf("a", "b")) // retention
        sweep()
        assertNull(store.arming()); assertEquals(3, store.pins().size); assertTrue("links are cleared, not kept for ever", links().all { it == null })
    }

    @Test fun aStaleWipeArmingThenRetentionThenAFreshDeleteAllDeletesOnlyPinsOfThatDeleteAll() = runBlocking<Unit> {
        ForgetListeners.clearForTests()
        val stale = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }); PinMomentLinks.endWipe(stale)
        fx.service.expirePhotos(listOf("b")) // retention takes pin 1's Moment while the stale arming sits there (no sweep ran)
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        assertEquals("the stale arming is gone; only pin 0 (alive now) is armed", mapOf(ids[0] to id1), store.arming()!!.pairs)
        assertTrue(PinMomentLinks.wipeCommittedStore(store, op)); db.wipeDao().wipeMemory()
        assertEquals(1, resolve(self = op)); PinMomentLinks.endWipe(op)
        assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    @Test fun aResumeNeverPromotesPairsUnlessTheEpochSaysTheWipeCommitted() = runBlocking<Unit> {
        val stale = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }); PinMomentLinks.endWipe(stale)
        pendingFlag = false
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = true, wipePending = { pendingFlag })
        assertTrue("nothing promoted", store.arming()?.dead.orEmpty().isEmpty()); db.wipeDao().wipeMemory(); PinMomentLinks.endWipe(op)
        assertEquals(0, resolve()); assertEquals(3, store.pins().size)
    }

    @Test fun aFreshDeleteAllWhileAnEarlierWipeIsStillPendingCarriesTheEarlierPairs() = runBlocking<Unit> {
        // wipe 1: epoch committed, its commit write failed, the process died
        val op1 = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        pendingFlag = true
        assertFalse(PinMomentLinks.wipeCommittedStore(UserPinStore(FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY)), op1))
        db.wipeDao().wipeMemory(); PinMomentLinks.endWipe(op1)
        // a pin made on a live Moment afterwards, then an immediate second Delete all (the epoch is still pending)
        fx.frame("c", (sec2 + 50) * 1000 + 5); fx.project(MomentId.format(sec2 + 50), MomentPartKind.FRAME to "c")
        assertTrue(store.write(store.pins() + pin(3, MomentId.format(sec2 + 50))))
        val op2 = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        assertEquals("the earlier confirmed pairs ride on the new arming", mapOf(ids[0] to id1, ids[1] to id2), store.arming()!!.dead)
        assertTrue(PinMomentLinks.wipeCommittedStore(store, op2)); db.wipeDao().wipeMemory()
        assertEquals(3, resolve(self = op2)); PinMomentLinks.endWipe(op2)
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aUserForgetWhileAnEarlierWipeIsStillPendingNeverCarriesTheWipesUncommittedPairs() = runBlocking<Unit> {
        val op1 = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        pendingFlag = true; db.wipeDao().wipeMemory(); PinMomentLinks.endWipe(op1) // epoch committed, never marked, process died: the flag stays set
        fx.frame("c", (sec2 + 50) * 1000 + 5); fx.project(MomentId.format(sec2 + 50), MomentPartKind.FRAME to "c")
        assertTrue(store.write(store.pins() + pin(3, MomentId.format(sec2 + 50))))
        var armedInside: Arming? = null
        explicit(target = { l -> l.filter { it == MomentId.format(sec2 + 50) }.toSet() }, wipePending = { pendingFlag }) { armedInside = store.arming(); fx.service.forgetMoment("c") }
        assertTrue("the user's Forget arms only its own pair", armedInside!!.dead.isEmpty() && armedInside!!.pairs.keys == setOf(ids[3]))
        assertEquals("only the pin the user confirmed is deleted; the wipe's pins keep (their links are cleared)", listOf("Pin 0", "Pin 1", "Pin 2"), names())
    }

    @Test fun anOperationInFlightThatArmedNothingDoesNotBlockASweepOrAnotherForget() = runBlocking<Unit> {
        var swept: Int? = -1; var inner = false
        explicit(target = none) { // nothing of this Forget reaches a pin: no arming
            assertEquals(null, store.arming())
            swept = sweep()
            explicit(target = only1) { fx.service.forgetMoment("a"); inner = true }
            true
        }
        assertEquals("the sweep ran (no arming to protect)", 0, swept)
        assertTrue(inner); assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    @Test fun aRangeForgetRacingADeleteAllCannotOverwriteTheWipesArming() = runBlocking<Unit> {
        val wipe = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag })
        val armed = store.arming()!!
        try { explicit(target = all) { org.junit.Assert.fail("the Forget must not run"); 0 }; org.junit.Assert.fail("expected PinArmFailed") } catch (e: PinArmFailed) { }
        assertEquals("the wipe's arming is untouched", armed, store.arming()); PinMomentLinks.endWipe(wipe)
    }

    @Test fun aDeleteAllRacingARangeForgetCannotOverwriteTheForgetsArming() = runBlocking<Unit> {
        var armedInside: Arming? = null; var refused = false
        explicit(target = all) {
            armedInside = store.arming()
            try { PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }); org.junit.Assert.fail("expected PinArmFailed") } catch (e: PinArmFailed) { refused = true }
            assertEquals("the Forget's arming is untouched", armedInside, store.arming()); fx.service.forgetMoment("a")
        }
        assertTrue(refused); assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    // ---- the invariant: every stored arming has a resolution at the next start ----

    @Test fun everyStoredArmingShapeIsResolvedByTheNextStart() = runBlocking<Unit> {
        ForgetListeners.clearForTests()
        for (kind in listOf("forget", "wipe")) for (committed in listOf(true, false)) for (pending in listOf(true, false)) for (carriedDead in listOf(true, false)) {
            val label = "kind=$kind committed=$committed wipePending=$pending carried=$carriedDead"
            rawPrefs.edit().clear().commit()
            val gone = MomentId.format(sec2 + 500) // pin 0's Moment has no photo: it is gone
            assertTrue(label, store.write(listOf(pin(0, gone), pin(1, id2), pin(2, null))))
            val dead = if (carriedDead || committed) mapOf(ids[0] to gone) else emptyMap()
            assertTrue(label, store.arm(Arming("x", kind, committed, if (committed) emptyMap() else mapOf(ids[0] to gone), dead)))
            pendingFlag = pending
            PlacePinning.underPinLock { PinMomentLinks.resolveStore(store, alive, save, null) { pendingFlag } } // the next start
            if (kind == "wipe" && !committed && pending) { // kept for the resume, which promotes it and finishes it
                assertTrue("$label: kept for the resume", store.arming() != null)
                val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = true, wipePending = { pendingFlag })
                resolve(self = op); PinMomentLinks.endWipe(op)
            }
            assertNull("$label: no arming survives", store.arming()); assertFalse(label, store.hasArmingRecord())
            PlacePinning.underPinLock { PinMomentLinks.sweepStore(store, alive) }
            assertTrue("$label: no pin keeps a link to a gone Moment", store.pins().none { it.momentId == gone })
            val expectDeleted = committed || carriedDead || (kind == "wipe" && !committed && pending)
            assertEquals("$label: pin 0 deleted iff its pair was in a committed or promoted set", !expectDeleted, store.pins().any { it.id == ids[0] })
        }
    }

    // ---- P2-2: only the confirmed pins are armed ----

    @Test fun aPinLinkedAfterTheConfirmationCheckIsNotArmedNorDeleted() = runBlocking<Unit> {
        assertTrue(store.write(store.pins() + pin(3, id1))) // linked to the same Moment between the check and the arm
        explicit(target = only1, allowed = setOf(ids[0])) { assertEquals("only the confirmed pin is armed", mapOf(ids[0] to id1), store.arming()!!.pairs); fx.service.forgetMoment("a") }
        assertEquals(listOf("Pin 1", "Pin 2", "Pin 3"), names()); assertNull("its link is cleared, the pin stays", store.pins().last().momentId)
    }

    @Test fun anEmptyConfirmedSetArmsNothing() = runBlocking<Unit> {
        explicit(target = only1, allowed = emptySet()) { assertNull(store.arming()); fx.service.forgetMoment("a") }
        assertEquals(3, store.pins().size)
    }

    // ---- P2-B: sweeps and settle never run while an explicit operation has armed ----

    @Test fun aScreenVisitSweepDuringAnArmedUncommittedForgetChangesNothing() = runBlocking<Unit> {
        var duringSweep: Int? = -1; var armedAfterSweep: Arming? = null; var linksAfterSweep: List<String?> = emptyList()
        explicit(target = only1) {
            fx.service.forgetMoment("a")
            duringSweep = sweep() // another thread's screen-visit sweep, after the Moment is gone but before the commit
            armedAfterSweep = store.arming(); linksAfterSweep = links()
            true
        }
        assertEquals(0, duringSweep); assertEquals(false, armedAfterSweep!!.committed); assertEquals(mapOf(ids[0] to id1), armedAfterSweep!!.pairs)
        assertEquals("the sweep did not clear pin 0's link, so the cascade still finds it", listOf(id1, id2, null), linksAfterSweep)
        assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    @Test fun aDeleteAllResumeIsNotRacedByAStartupSettle() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false, wipePending = { pendingFlag }); PinMomentLinks.wipeCommittedStore(store, op); db.wipeDao().wipeMemory(); PinMomentLinks.endWipe(op)
        pendingFlag = true
        val op2 = PinMomentLinks.beginWipeStore(store, alive, save, resume = true, wipePending = { pendingFlag })
        assertEquals("ForgetStartup.settle while the resume runs", 0, resolve()); assertEquals(0, sweep())
        assertEquals("nothing was dropped or changed", 3, store.pins().size); assertTrue(store.arming()!!.committed)
        assertEquals(2, resolve(self = op2)); PinMomentLinks.endWipe(op2)
        assertEquals(listOf("Pin 2"), names())
    }

    // ---- arming must be stored ----

    private class FailingPrefs(private val d: SharedPreferences, var failKey: String? = null) : SharedPreferences by d {
        override fun edit(): SharedPreferences.Editor {
            val e = d.edit()
            return object : SharedPreferences.Editor by e {
                var k: String? = null
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { k = key; e.putString(key, value); return this }
                override fun commit(): Boolean = if (k == failKey) false else e.commit()
            }
        }
    }

    @Test fun ifTheArmingCannotBeStoredTheForgetIsNotRun() = runBlocking<Unit> {
        val fp = FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY)
        var ran = false
        try { explicit(UserPinStore(fp)) { ran = true; fx.service.forgetMoment("a") }; org.junit.Assert.fail("expected PinArmFailed") } catch (e: PinArmFailed) { }
        assertFalse("nothing was forgotten", ran); assertEquals(3, store.pins().size); assertNull(store.arming())
        assertEquals(listOf("a", "b"), db.frameDao().all().map { it.id }.sorted())
    }

    @Test fun withNothingToArmNoArmingIsNeededAndTheForgetRuns() = runBlocking<Unit> {
        val fp = FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY)
        explicit(UserPinStore(fp), target = none) { fx.service.forgetMoment("a") }
        assertEquals(listOf("b"), db.frameDao().all().map { it.id })
    }

    @Test fun deleteAllRefusesBeforeDeletingWhenTheArmingCannotBeStored() = runBlocking<Unit> {
        val fp = FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY)
        try { PinMomentLinks.beginWipeStore(UserPinStore(fp), alive, save, resume = false, wipePending = { pendingFlag }); org.junit.Assert.fail("expected PinArmFailed") } catch (e: PinArmFailed) { }
        assertEquals(3, store.pins().size)
    }

    @Test fun aRangeForgetThatWouldDeletePinsNeedsTheConfirmedPinIdSet() {
        val two = setOf(ids[0], ids[1])
        assertFalse(ForgetActions.rangeNeedsConfirmation(emptySet(), null)); assertFalse(ForgetActions.rangeNeedsConfirmation(two, two))
        assertTrue(ForgetActions.rangeNeedsConfirmation(two, null)); assertTrue(ForgetActions.rangeNeedsConfirmation(two, setOf(ids[0])))
        assertTrue("same count, other pins", ForgetActions.rangeNeedsConfirmation(two, setOf(ids[0], ids[2])))
        val actions = src("ForgetActions.kt")
        assertTrue("the range arming is stored inside the DataOpGate", "DataOpGate.shared.delete { com.rokid.context.work.frames.place.PinMomentLinks.explicit(context, { ids -> ids.filter(inRange)" in actions && "forgetRangeLocked" in actions)
        assertTrue("the arming is bound to the confirmed set", "allowedPins = confirmedPins ?: emptySet()" in actions)
        assertTrue("the refusal runs before the cascade", actions.indexOf("rangeNeedsConfirmation(going.ids, confirmedPins)") in 1 until actions.indexOf("PinMomentLinks.explicit(context, { ids -> ids.filter(inRange).toSet() }"))
    }

    // ---- non-explicit removal: the pin is NEVER deleted, only its link ----

    @Test fun retentionExpiryClearsTheLinkAndKeepsThePin() = runBlocking<Unit> {
        fx.service.expirePhotos(listOf("b"))
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(listOf(id1, null, null), links())
    }

    @Test fun theSweepIsIdempotentAndAnUnreadablePinListIsLeftAlone() = runBlocking<Unit> {
        assertEquals(0, sweep()); assertEquals(listOf(id1, id2, null), links())
        rawPrefs.edit().putString("pins", "garbage").commit()
        assertNull(PinMomentLinks.sweepStore(store) { emptySet() })
        assertEquals("garbage", rawPrefs.getString("pins", null))
    }

    @Test fun aListenerThatFailsNeverBreaksAForget() = runBlocking<Unit> {
        ForgetListeners.register(PinMomentLinks.listenerOf({ error("boom") }, { error("boom") }))
        fx.service.forgetMoment("a")
        assertEquals(3, store.pins().size)
    }

    // ---- the confirmation count and the wiring ----

    @Test fun theConfirmationCountsExactlyThePinsTheForgetWouldDelete() = runBlocking<Unit> {
        val engine = com.rokid.context.memory.forget.ScopedForget(db, fx.service)
        val plan = engine.plan(com.rokid.context.memory.forget.ForgetTarget.Photo("a"), com.rokid.context.memory.forget.ForgetScope.PHOTO)
        assertEquals(setOf(id1), engine.momentsReached(plan, listOf(id1, id2)))
        fx.frame("a2", MomentForgetFixture.MS + 7)
        assertEquals("a second photo of the same second keeps the Moment alive", emptySet<String>(), engine.momentsReached(plan, listOf(id1, id2)))
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private fun src(name: String) = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" }.first { it.isFile && it.name == name && "/src/main/" in it.path }.readText()

    @Test fun theInventoryNamesTheCascadeAndEveryExplicitPathIsWrapped() {
        val why = com.rokid.context.memory.forget.DeletionGraph.PREFS.single { it.name == "place_pins" }.why
        assertTrue("the inventory entry must say an explicit Forget deletes the pin and an expiry only clears the link", "reference Moment" in why && "EXPLICIT" in why && "NEVER deleted by an expiry" in why)
        val wipe = src("MemoryWipe.kt")
        assertTrue("Delete all arms first, marks the commit and settles at the end", "PinMomentLinks.beginWipe(context, resume)" in wipe && "PinMomentLinks.wipeCommitted(context, pinOp)" in wipe && "PinMomentLinks.abortWipe(context, pinOp)" in wipe && "PinMomentLinks.settle(context, pinOp)" in wipe)
        val actions = src("ForgetActions.kt")
        assertTrue("every gated Forget runs inside the cascade with its target set", "PinMomentLinks.explicit(context, target, committed = { o: Outcome -> o.committed }" in actions)
        assertTrue("an arming failure is a typed outcome", "Outcome.PIN_PREP_FAILED" in actions)
        assertTrue("the range Forget runs inside the cascade", "PinMomentLinks.explicit(context, { ids -> ids.filter(inRange).toSet() }" in actions)
        assertTrue("the crash resume at start", "PinMomentLinks.settle(context)" in actions)
        assertFalse("no 'reached = {true}' resume shortcut", "reached" in src("PinMomentLinks.kt"))
        assertTrue("the listener is registered at start", "PinMomentLinks.listener(this)" in src("ContextApp.kt"))
        assertTrue("the settings export strips the link and the arming", "withoutLinks" in src("ConfigExport.kt") && "ARMED_KEY" in src("ConfigExport.kt"))
        listOf("RetentionWorker.kt", "SuspectSweepWorker.kt", "NightIndexWorker.kt").forEach { assertFalse("$it must not delete pins", "UserPinStore" in src(it) || "PinMomentLinks.explicit" in src(it)) }
    }

    @Test fun noMatchingCodeReadsTheLinkOrThePinTime() {
        val readers = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && Regex("""\.(momentId|pinnedAt)\b""").containsMatchIn(it.readText()) && "UserPlace" in it.readText() }.map { it.name }.toSet()
        assertFalse("matching must not read the link or pinnedAt in this slice: $readers", readers.any { it.startsWith("PlaceSampler") || it.startsWith("PlaceMatch") || it == "PlacePack.kt" || it == "ProactiveGraph.kt" })
    }
}
