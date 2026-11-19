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
 * Slice 1.4.2 "Pin to Moment", privacy side. An EXPLICIT user Forget of a pin's reference Moment (a photo, a range, Delete all, also its crash resume) deletes the pin with it, but ONLY through an arming that
 * belongs to that one operation, names its (pin, Moment) pairs and is committed only when the Forget really committed. Every NON-explicit removal (retention expiry, a sweep) never deletes a pin, it only
 * clears the link; a stale or uncommitted arming is dropped, never acted on, and never blocks the sweeps. Real Forget code on an in-memory database drives the real cascade and sweep.
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
    private val ids = (0..2).map { "user:00000000-0000-0000-0000-00000000000$it" }
    private fun pin(i: Int, moment: String?) = UserPlace(ids[i], "Pin $i", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = moment)

    private val alive: suspend (List<String>) -> Set<String> = { l -> db.momentDao().photoMomentsOf(l).map { it.momentId }.toSet() }
    private val save: suspend (List<UserPlace>) -> Boolean = { l -> store.write(l) }

    private suspend fun <T> explicit(st: UserPinStore = store, reached: (String) -> Boolean = { true }, committed: (T) -> Boolean = { true }, forget: suspend () -> T): T =
        PinMomentLinks.explicitStore(st, alive, save, reached, committed, forget)
    private suspend fun sweep() = PlacePinning.underPinLock { PinMomentLinks.sweepAll(store, alive, save) }
    private suspend fun resolve() = PlacePinning.underPinLock { PinMomentLinks.resolveStore(store, alive, save) }

    @Before fun setUp() = runBlocking<Unit> {
        rawPrefs.edit().clear().commit()
        ForgetListeners.clearForTests()
        ForgetListeners.register(PinMomentLinks.listenerOf({ PinMomentLinks.sweepGuarded(store, alive, save) }, { resolve() })) // the production listener: it leaves the pins alone while an explicit operation runs
        fx.frame("a", MomentForgetFixture.MS + 5); fx.project(id1, MomentPartKind.FRAME to "a")
        fx.frame("b", sec2 * 1000 + 5); fx.project(id2, MomentPartKind.FRAME to "b")
        assertTrue(store.write(listOf(pin(0, id1), pin(1, id2), pin(2, null))))
    }
    @After fun tearDown() { ForgetListeners.clearForTests(); db.close(); rawPrefs.edit().clear().commit() }

    private fun names() = store.pins().map { it.name }
    private fun links() = store.pins().map { it.momentId }
    private val pairs get() = mapOf(ids[0] to id1, ids[1] to id2)

    // ---- explicit Forget: the pin goes with its Moment ----

    @Test fun anExplicitForgetOfThePhotoDeletesThePinsOfThatMomentOnly() = runBlocking<Unit> {
        explicit { fx.service.forgetMoment("a") }
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertEquals(listOf(id2, null), links())
        assertNull("armed state is gone", store.arming())
    }

    @Test fun anExplicitMomentAndRangeForgetDeleteThePinsInTheRangeOnly() = runBlocking<Unit> {
        explicit { fx.forget.forgetMoments(listOf(id1)) }
        assertEquals(listOf("Pin 1", "Pin 2"), names())
        explicit(reached = { id -> MomentId.parse(id) == sec2 }) { fx.forget.forgetRange(sec2 * 1000, sec2 * 1000 + 999) }
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aRangeForgetDoesNotDeleteAPinWhoseMomentWentForAnotherReasonInTheSameOperation() = runBlocking<Unit> {
        explicit(reached = { id -> MomentId.parse(id) == MomentForgetFixture.SEC }) { fx.service.forgetMoment("a"); fx.service.expirePhotos(listOf("b")) }
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull("link cleared, pin kept", store.pins().first().momentId)
    }

    @Test fun forgettingAFactNeverDeletesThePinBecauseThePhotoStays() = runBlocking<Unit> {
        fx.memory("m1", "a")
        explicit { fx.service.forgetFact("m1") }
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(listOf(id1, id2, null), links())
    }

    @Test fun aForgetThatDidNotCommitKeepsThePinsAndDropsTheArming() = runBlocking<Unit> {
        explicit(committed = { _: Boolean -> false }) { true }
        assertEquals(3, store.pins().size); assertNull(store.arming())
        try { explicit<Unit> { error("boom") } } catch (e: IllegalStateException) { }
        assertEquals(3, store.pins().size); assertNull(store.arming())
    }

    // ---- Delete all ----

    @Test fun deleteAllDeletesEveryPinThatHadAMomentAndKeepsTheRest() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false)
        assertTrue(PinMomentLinks.wipeCommittedStore(store, op)); db.wipeDao().wipeMemory(); PinMomentLinks.endWipe()
        assertEquals(2, resolve()); assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aCrashedDeleteAllResumesAndDeletesThePinsEvenThoughTheMomentsAreAlreadyGone() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false); PinMomentLinks.wipeCommittedStore(store, op); db.wipeDao().wipeMemory() // crash before the cascade
        PinMomentLinks.endWipe()
        PinMomentLinks.beginWipeStore(store, alive, save, resume = true); PinMomentLinks.endWipe() // the resume keeps the committed arming
        assertEquals(pairs, store.arming()!!.pairs); assertTrue(store.arming()!!.committed)
        assertEquals(2, resolve()); assertEquals(listOf("Pin 2"), names())
    }

    @Test fun deleteAllThatAbortsAtTheEpochCommitThenExpiryThenAnUnrelatedForgetNeverDeletesAPin() = runBlocking<Unit> {
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false)
        PinMomentLinks.abortWipeStore(store, op); PinMomentLinks.endWipe() // commitWipe threw: nothing was deleted
        assertNull(store.arming())
        fx.service.expirePhotos(listOf("b")) // retention: the listener sweep clears the link
        explicit { fx.service.forgetMoment("a") } // an unrelated explicit Forget
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull("pin 1 keeps no link but is NOT deleted", store.pins().first().momentId)
    }

    @Test fun anArmingThatNeverCommittedIsDroppedNotCarriedIntoTheNextForget() = runBlocking<Unit> {
        // a crash right after arming, before the epoch commit: the arming stays uncommitted
        val op = PinMomentLinks.beginWipeStore(store, alive, save, resume = false); PinMomentLinks.endWipe()
        assertEquals(false, store.arming()!!.committed); assertEquals(op, store.arming()!!.op)
        fx.service.expirePhotos(listOf("b"))
        explicit { fx.service.forgetMoment("a") }
        assertEquals(listOf("Pin 1", "Pin 2"), names())
    }

    // ---- crash between an explicit Forget's commit and its cascade ----

    @Test fun aCommittedArmingIsFinishedAtNextStart() = runBlocking<Unit> {
        ForgetListeners.clearForTests() // (in production the in-flight Forget keeps the sweeps away from its arming)
        assertTrue(store.arm(Arming("op1", "forget", false, pairs)))
        fx.service.forgetMoment("a") // committed; the process dies here
        assertTrue(store.markCommitted("op1")) // (the Forget's result is marked committed just before the cascade)
        assertEquals(1, resolve())
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull(store.arming())
    }

    @Test fun anUncommittedArmingAtStartIsDroppedAndTheFollowingSweepOnlyClearsTheLink() = runBlocking<Unit> {
        assertTrue(store.arm(Arming("op1", "forget", false, pairs)))
        fx.service.expirePhotos(listOf("a")) // expiry, not a Forget; the listener sweep ran with the stale arming present
        assertEquals(3, store.pins().size); assertEquals(listOf(null, id2, null), links()); assertNull(store.arming())
    }

    @Test fun anArmedPinPlusExpiryOnlyClearsTheLink() = runBlocking<Unit> {
        assertTrue(store.arm(Arming("op1", "forget", false, pairs)))
        fx.service.expirePhotos(listOf("a", "b"))
        assertEquals(3, store.pins().size); assertTrue(links().all { it == null })
    }

    @Test fun anArmingOfAnotherPinOrMomentPairIsNeverActedOn() = runBlocking<Unit> {
        assertTrue(store.arm(Arming("op1", "forget", true, mapOf(ids[0] to id2)))) // committed, but pin 0 now points at another Moment than the armed one
        fx.service.forgetMoment("b")
        assertEquals(0, resolve()); assertEquals(3, store.pins().size)
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

    @Test fun withNoLinkedPinNoArmingIsNeededAndTheForgetRuns() = runBlocking<Unit> {
        assertTrue(store.write(listOf(pin(2, null))))
        val fp = FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY)
        explicit(UserPinStore(fp)) { fx.service.forgetMoment("a") }
        assertEquals(listOf("b"), db.frameDao().all().map { it.id })
    }

    @Test fun deleteAllRefusesBeforeDeletingWhenTheArmingCannotBeStored() = runBlocking<Unit> {
        val fp = FailingPrefs(rawPrefs, failKey = UserPinStore.ARMED_KEY)
        try { PinMomentLinks.beginWipeStore(UserPinStore(fp), alive, save, resume = false); org.junit.Assert.fail("expected PinArmFailed") } catch (e: PinArmFailed) { }
        assertEquals(3, store.pins().size)
    }

    @Test fun aRangeForgetThatWouldDeletePinsNeedsTheConfirmedCount() {
        assertFalse(ForgetActions.rangeNeedsConfirmation(0, null)); assertFalse(ForgetActions.rangeNeedsConfirmation(2, 2))
        assertTrue(ForgetActions.rangeNeedsConfirmation(2, null)); assertTrue(ForgetActions.rangeNeedsConfirmation(2, 1))
        val actions = src("ForgetActions.kt")
        assertTrue("the refusal runs before the cascade", actions.indexOf("rangeNeedsConfirmation(going.count, confirmedPins)") in 1 until actions.indexOf("PinMomentLinks.explicit(context, reached = inRange)"))
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
        assertTrue("Delete all arms first, marks the commit and settles at the end", "PinMomentLinks.beginWipe(context, resume)" in wipe && "PinMomentLinks.wipeCommitted(context, pinOp)" in wipe && "PinMomentLinks.abortWipe(context, pinOp)" in wipe && "PinMomentLinks.settle(context)" in wipe)
        val actions = src("ForgetActions.kt")
        assertTrue("every gated Forget runs inside the cascade", "PinMomentLinks.explicit(context, committed = { o: Outcome -> o.committed })" in actions)
        assertTrue("an arming failure is a typed outcome", "Outcome.PIN_PREP_FAILED" in actions)
        assertTrue("the range Forget runs inside the cascade", "PinMomentLinks.explicit(context, reached = inRange)" in actions)
        assertTrue("the crash resume at start", "PinMomentLinks.settle(context)" in actions)
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
