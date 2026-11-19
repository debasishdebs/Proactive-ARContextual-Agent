package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.forget.MomentForgetFixture
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPlace
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
 * Slice 1.4.2 "Pin to Moment", privacy side. An EXPLICIT user Forget of a pin's reference Moment (a photo, a range, Delete all, also its crash resume) deletes the pin with it (armed before, cascaded after);
 * every NON-explicit removal (retention expiry, a sweep) never deletes a pin, it only clears the link ("Needs a Moment"). Real Forget code on an in-memory database drives the real cascade and sweep.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PinMomentLinksTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val fx = MomentForgetFixture(db)
    private val store get() = UserPinStore(ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE))
    private val sec2 = MomentForgetFixture.SEC + 100
    private val id1 = fx.id
    private val id2 = MomentId.format(sec2)
    private val ids = (0..2).map { "user:00000000-0000-0000-0000-00000000000$it" }
    private fun pin(i: Int, moment: String?) = UserPlace(ids[i], "Pin $i", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = moment)

    private suspend fun alive(l: List<String>) = db.momentDao().photoMomentsOf(l).map { it.momentId }.toSet()
    private var saves = 0
    private suspend fun save(l: List<UserPlace>) = store.write(l).also { if (it) saves++ }
    private suspend fun arm(all: Boolean = false) = PlacePinning.underPinLock { PinMomentLinks.armStore(store, all, ::alive) }
    private suspend fun cascade(reached: (String) -> Boolean = { true }) = PlacePinning.underPinLock { PinMomentLinks.cascadeStore(store, ::alive, ::save, reached) }
    private suspend fun sweep() = PlacePinning.underPinLock { if (store.armed().isNotEmpty()) 0 else PinMomentLinks.sweepStore(store, ::alive) }
    /** An explicit Forget as ForgetActions runs it: arm, the Forget, cascade. */
    private suspend fun explicit(reached: (String) -> Boolean = { true }, forget: suspend () -> Unit) { arm(); try { forget() } finally { cascade(reached) } }

    @Before fun setUp() = runBlocking<Unit> {
        ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ForgetListeners.clearForTests()
        ForgetListeners.register(PinMomentLinks.listenerOf({ sweep() }, { arm(all = true); cascade() })) // the production listener's shape
        fx.frame("a", MomentForgetFixture.MS + 5); fx.project(id1, MomentPartKind.FRAME to "a")
        fx.frame("b", sec2 * 1000 + 5); fx.project(id2, MomentPartKind.FRAME to "b")
        assertTrue(store.write(listOf(pin(0, id1), pin(1, id2), pin(2, null))))
    }
    @After fun tearDown() { ForgetListeners.clearForTests(); db.close(); ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit() }

    private fun names() = store.pins().map { it.name }
    private fun links() = store.pins().map { it.momentId }

    // ---- explicit Forget: the pin goes with its Moment ----

    @Test fun anExplicitForgetOfThePhotoDeletesThePinsOfThatMomentOnly() = runBlocking<Unit> {
        explicit { fx.service.forgetMoment("a") }
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertEquals(listOf(id2, null), links())
        assertTrue("armed state is gone", store.armed().isEmpty())
    }

    @Test fun anExplicitMomentAndRangeForgetDeleteThePinsInTheRangeOnly() = runBlocking<Unit> {
        explicit { fx.forget.forgetMoments(listOf(id1)) }
        assertEquals(listOf("Pin 1", "Pin 2"), names())
        explicit(reached = { id -> MomentId.parse(id) == sec2 }) { fx.forget.forgetRange(sec2 * 1000, sec2 * 1000 + 999) }
        assertEquals(listOf("Pin 2"), names()); assertNull(store.pins().single().momentId) // the pin that never had a Moment stays
    }

    @Test fun aRangeForgetDoesNotDeleteAPinWhoseMomentGoneForAnotherReason() = runBlocking<Unit> {
        // the range names only second 1; the Moment of second 2 vanishes by expiry in the same window: its pin must not be deleted by the range's cascade
        explicit(reached = { id -> MomentId.parse(id) == MomentForgetFixture.SEC }) { fx.service.forgetMoment("a"); fx.service.expirePhotos(listOf("b")) }
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertNull("link cleared, pin kept", store.pins().first().momentId)
    }

    @Test fun forgettingAFactNeverDeletesThePinBecauseThePhotoStays() = runBlocking<Unit> {
        fx.memory("m1", "a")
        explicit { fx.service.forgetFact("m1") }
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(listOf(id1, id2, null), links())
    }

    @Test fun deleteAllDeletesEveryPinThatHadAMomentAndKeepsTheRest() = runBlocking<Unit> {
        arm(all = true); db.wipeDao().wipeMemory(); cascade()
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun theListenerOnDeleteAllIsTheSafetyNetForTheSameCascade() = runBlocking<Unit> {
        db.wipeDao().wipeMemory(); ForgetListeners.deleteAll()
        assertEquals(listOf("Pin 2"), names())
    }

    @Test fun aCrashAfterTheForgetCommittedIsFinishedFromTheArmingAtNextStart() = runBlocking<Unit> {
        arm()
        fx.service.forgetMoment("a") // committed; the process dies here: no cascade
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(setOf(ids[0], ids[1]), store.armed())
        assertEquals("a sweep while armed leaves the links alone", 0, sweep()); assertEquals(listOf(id1, id2, null), links())
        assertEquals(1, cascade()) // ForgetStartup
        assertEquals(listOf("Pin 1", "Pin 2"), names()); assertTrue(store.armed().isEmpty())
    }

    @Test fun aCrashedDeleteAllResumesAndDeletesThePinsEvenThoughTheMomentsAreAlreadyGone() = runBlocking<Unit> {
        arm(all = true); db.wipeDao().wipeMemory() // epoch committed, wipe done, crash before the cascade
        arm(all = true) // the resume arms again (union with the first arming)
        assertEquals(2, cascade()); assertEquals(listOf("Pin 2"), names())
    }

    @Test fun anArmedForgetThatFailedKeepsThePinsAndDisarms() = runBlocking<Unit> {
        explicit { /* the Forget did nothing (refused, rolled back) */ }
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(listOf(id1, id2, null), links()); assertTrue(store.armed().isEmpty())
    }

    @Test fun anUnwritablePinListStaysArmedSoTheStartupRetries() = runBlocking<Unit> {
        arm(); fx.service.forgetMoment("a")
        assertNull(PlacePinning.underPinLock { PinMomentLinks.cascadeStore(store, ::alive, { false }) })
        assertEquals(3, store.pins().size); assertEquals(setOf(ids[0], ids[1]), store.armed())
    }

    // ---- non-explicit removal: the pin is NEVER deleted, only its link ----

    @Test fun retentionExpiryClearsTheLinkAndKeepsThePin() = runBlocking<Unit> {
        fx.service.expirePhotos(listOf("b")) // the listener sweeps (nothing armed)
        assertEquals(listOf("Pin 0", "Pin 1", "Pin 2"), names()); assertEquals(listOf(id1, null, null), links())
    }

    @Test fun aRetentionPassWithNoUserForgetNeverDeletesAPinEvenForEveryMoment() = runBlocking<Unit> {
        fx.service.expirePhotos(listOf("a", "b"))
        assertEquals(3, store.pins().size); assertTrue(links().all { it == null })
    }

    @Test fun theSweepIsIdempotentAndAnUnreadablePinListIsLeftAlone() = runBlocking<Unit> {
        assertEquals(0, sweep()); assertEquals(listOf(id1, id2, null), links())
        ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE).edit().putString("pins", "garbage").commit()
        assertNull(PinMomentLinks.sweepStore(store) { emptySet() }); assertNull(cascade())
        assertEquals("garbage", ctx.getSharedPreferences(UserPinStore.PREFS, Context.MODE_PRIVATE).getString("pins", null))
    }

    @Test fun aListenerThatFailsNeverBreaksAForget() = runBlocking<Unit> {
        ForgetListeners.register(PinMomentLinks.listenerOf({ error("boom") }, { error("boom") }))
        fx.service.forgetMoment("a")
        assertEquals(3, store.pins().size)
    }

    // ---- the confirmation count and the wiring ----

    @Test fun theConfirmationCountsExactlyThePinsTheForgetWouldDelete() = runBlocking<Unit> {
        // pinsGoing needs the app graphs; its rule is "the linked live Moments the Forget leaves without a photo": the same ScopedForget answer the cascade uses
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
        assertTrue("Delete all arms first and cascades at the end (also on its resume)", "PinMomentLinks.armBefore(context, all = true)" in wipe && "PinMomentLinks.cascadeAfter(context)" in wipe)
        val actions = src("ForgetActions.kt")
        assertTrue("every gated Forget runs inside the cascade", "PinMomentLinks.explicit(context) { runLocked(" in actions)
        assertTrue("the range Forget runs inside the cascade", "PinMomentLinks.explicit(context, reached = inRange)" in actions)
        assertTrue("the crash resume at start", "PinMomentLinks.cascadeAfter(context)" in actions)
        assertTrue("the listener is registered at start", "PinMomentLinks.listener(this)" in src("ContextApp.kt"))
        assertTrue("the settings export strips the link and the arming", "withoutLinks" in src("ConfigExport.kt") && "ARMED_KEY" in src("ConfigExport.kt"))
        // the NON-explicit workers never reach the pin store: they only tell the listener through ForgetService
        listOf("RetentionWorker.kt", "SuspectSweepWorker.kt", "NightIndexWorker.kt").forEach { assertFalse("$it must not delete pins", "UserPinStore" in src(it) || "PinMomentLinks.explicit" in src(it)) }
    }

    @Test fun noMatchingCodeReadsTheLink() {
        val readers = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" && it.name != "node_modules" }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && Regex("""\.momentId\b""").containsMatchIn(it.readText()) && "UserPlace" in it.readText() }.map { it.name }.toSet()
        assertFalse("matching must not read the link in this slice: $readers", readers.any { it.startsWith("PlaceSampler") || it.startsWith("PlaceMatch") || it == "PlacePack.kt" || it == "ProactiveGraph.kt" })
    }
}
