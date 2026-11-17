package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.place.ZoneFence
import com.rokid.context.memory.retrieval.RetrievalSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger

/** Z1 (proxy ruling 2026-11-17): the pause-zone fence on Ask. A zone flips at EVERY check of the Ask path (like PlaceSamplerR4Test); the refusal text; delivery withheld; in-flight cancel; the switch; fail closed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskZoneFenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private val modelCalls = AtomicInteger()
    private var onModel: suspend () -> Unit = {}
    private val okReply = """{"answer":"Milk is low.","sources":["rokid_mem_1"],"proposedAction":{"type":"NOTE_ADD","text":"x"}}"""
    private var reply: () -> String = { okReply }

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { modelCalls.incrementAndGet(); onModel(); reply() }
        TestAi.bindAll(ai)
        runBlocking { MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty")) }
        AnswerService.zonePollMs = 1_000_000L // the checks are deterministic here; the in-flight test lowers it
    }

    @After fun tearDown() {
        AnswerService.zonePollMs = 100L
        AskZoneFence.resetForTests()
        ctx.getSharedPreferences(AskZoneFence.ZONE_STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun askCount(q: String? = null): Int = MemoryGraph.get(ctx).db.openHelper.readableDatabase
        .query(if (q == null) "SELECT COUNT(*) FROM ask" else "SELECT COUNT(*) FROM ask WHERE question = ?", if (q == null) emptyArray() else arrayOf<Any>(q)).use { it.moveToFirst(); it.getInt(0) }

    private val intakes = mutableListOf<String>()
    private fun service(guard: AskZoneGuard) = AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() }, zoneGuard = guard,
        proposals = ProposalIntake { _, id, _, _ -> intakes += id; "p" })

    @Test fun theRefusalIsPlainText() {
        assertEquals("Paused: you're in a pause zone", AskActions.ZONE_PAUSED)
        assertEquals(AskOutcome.Failed("Paused: you're in a pause zone"), runBlocking { service { true }.answer("milk?", "phone_typed") })
    }

    @Test fun aZoneAtTheStartMakesNoModelCallAndSavesNothing() = runBlocking {
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), service { true }.answer("milk?", "phone_typed"))
        assertEquals(0, modelCalls.get()); assertTrue(askCount() == 0); assertTrue(intakes.isEmpty())
    }

    @Test fun anEvalAskIsRefusedLikeAnyOtherWhileAZoneIsActiveAndBehavesAsBeforeWhenNone() = runBlocking {
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), service { true }.answer("milk?", AskActions.EVAL_SOURCE))
        assertEquals(0, modelCalls.get())
        val ok = service(AskZoneGuard { false }).answer("milk?", AskActions.EVAL_SOURCE)
        assertTrue(ok is AskOutcome.Answered); assertTrue("an eval ask takes no proposal in", intakes.isEmpty())
    }

    /** The zone flips at each successive check of the path: nothing is dispatched, saved or delivered after it, except the last (after the commit), where only the DELIVERY is withheld. */
    @Test fun aZoneFlipsAtEveryCheckOfTheAskPath() = runBlocking {
        val checks = AtomicInteger()
        assertTrue(service { checks.incrementAndGet(); false }.answer("milk?", "phone_typed") is AskOutcome.Answered)
        val total = checks.get()
        assertTrue("the path has several checks: $total", total >= 7) // start, retrieval, dispatch, the dispatch slot, after the model, after the save, after the route
        for (k in 1..total) {
            modelCalls.set(0); intakes.clear()
            val n = AtomicInteger()
            val r = service { n.incrementAndGet() >= k }.answer("milk $k?", "phone_typed")
            if (k < total - 1) {
                assertEquals("flip at check $k", AskOutcome.Failed(AskActions.ZONE_PAUSED), r)
                assertTrue("no proposal at $k", intakes.isEmpty())
                assertTrue("before the dispatch slot ($k < ${total - 2}) no model call is made", k >= total - 2 || modelCalls.get() == 0)
            } else {
                r as AskOutcome.Answered
                assertTrue("delivery withheld at the last checks ($k)", r.deliveryWithheld); assertNull(r.route); assertEquals(AskActions.ZONE_DELIVERY_WITHHELD, r.notice)
                assertEquals("the answer is saved for the phone", "Milk is low.", MemoryGraph.get(ctx).asks.byId(r.askId)!!.answer)
                if (k == total - 1) assertTrue("no proposal while withheld", intakes.isEmpty()) // the last check is after the proposal intake and the route: only the delivery is withheld
            }
            assertEquals("a refused ask leaves no row; only the withheld ones are kept", if (k < total - 1) 0 else 1, askCount("milk $k?"))
        }
    }

    @Test fun aZoneThatBeginsWhileTheModelAnswersCancelsItAndNothingIsSaved() = runBlocking {
        AnswerService.zonePollMs = 20L
        var blocked = false
        var cancelled = false
        onModel = { blocked = true; try { awaitCancellation() } finally { cancelled = true } }
        val r = service { blocked }.answer("milk?", "phone_typed")
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), r)
        assertTrue("the in-flight call was cancelled", cancelled); assertEquals(1, modelCalls.get())
        assertTrue(askCount() == 0); assertTrue(intakes.isEmpty())
    }

    @Test fun aRefusedAskIsNeverRetriedByItself() = runBlocking {
        val s = service { true }
        repeat(3) { assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), s.answer("milk?", "phone_typed")) }
        assertEquals(0, modelCalls.get())
    }

    @Test fun phoneBrowsingOfExistingDataStaysAvailableInAZone() = runBlocking {
        service(AskZoneGuard { false }).answer("milk?", "phone_typed")
        val g = MemoryGraph.get(ctx)
        AskZoneFence.fence = ZoneFence().also { it.hold() }
        assertTrue(AskZoneFence.blocked())
        assertEquals("the stored ask is still readable", 1, askCount())
        assertTrue(g.memories.search("milk", 5).isNotEmpty())
    }

    @Test fun theProductionGuardFollowsTheFenceTheSwitchAndFailsClosed() {
        val fence = ZoneFence(); AskZoneFence.fence = fence
        assertFalse(AskZoneFence.blocked())
        fence.hold(); assertTrue("held fence", AskZoneFence.blocked())
        AskZoneFence.enabledProvider = { false }; assertFalse("switch OFF = today's behaviour", AskZoneFence.blocked())
        AskZoneFence.enabledProvider = { true }; fence.release(); assertFalse(AskZoneFence.blocked())
        AskZoneFence.extraBlocked = { true }; assertTrue("the controller's flags", AskZoneFence.blocked())
        AskZoneFence.extraBlocked = { error("unreadable") }; assertTrue("a read error is blocked (fail closed)", AskZoneFence.blocked())
        AskZoneFence.enabledProvider = { error("prefs") }; assertTrue("an unreadable switch means ON", AskZoneFence.blocked())
    }

    /** A guard over a real [ZoneFence] with a ticket per ask (what AskZoneFence is in production). */
    private fun fenced(fence: ZoneFence) = object : AskZoneGuard {
        override fun blocked() = fence.blocked()
        override fun ticket() = fence.ticket()
    }

    /** Codex z1 r1 P1-2: no poll (it is a million ms): the fence closing cancels the in-flight dispatch synchronously. */
    @Test fun theFenceClosingCancelsTheInFlightModelCallWithoutAPoll() = runBlocking {
        val fence = ZoneFence()
        var cancelled = false
        onModel = { fence.hold(); try { awaitCancellation() } finally { cancelled = true } }
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), service(fenced(fence)).answer("milk?", "phone_typed"))
        assertTrue("the in-flight call was cancelled by the fence", cancelled); assertEquals(1, modelCalls.get())
        assertEquals(0, askCount()); assertTrue(intakes.isEmpty())
    }

    /** The validation retry is a dispatch too: a zone that starts while the first reply is judged ends the second call before it is sent. */
    @Test fun aValidationRetryAfterTheFenceClosedIsNeverSent() = runBlocking {
        val fence = ZoneFence()
        reply = { fence.hold(); "not json" } // the first reply is invalid; the zone starts meanwhile
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), service(fenced(fence)).answer("milk?", "phone_typed"))
        assertEquals("no second dispatch after the fence closed", 1, modelCalls.get()); assertEquals(0, askCount())
    }

    /** A zone that starts between the last check and the provider write (inside the call permit) ends the dispatch there. */
    @Test fun aZoneStartingBetweenTheLastCheckAndTheDispatchRefusesTheCall() = runBlocking {
        val fence = ZoneFence()
        // the fence closes after retrieval's last check and before the call: simulate with the guard's own ticket
        val g = object : AskZoneGuard {
            var n = 0
            override fun blocked(): Boolean = fence.blocked()
            override fun ticket() = fence.ticket()
            override fun check() { super.check(); if (++n == 3) fence.hold() } // after the dispatch check passed
        }
        val s2 = AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() }, zoneGuard = g, proposals = ProposalIntake { _, _, _, _ -> "p" })
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), s2.answer("milk?", "phone_typed"))
        assertEquals("the dispatch slot saw the closed fence: nothing was sent", 0, modelCalls.get()); assertEquals(0, askCount())
    }

    /** Codex z1 r1 P1-1: a new process whose controller has not judged: the persisted protect flag refuses, with a model call never made, until the controller says clear. */
    @Test fun aColdStartWithAPersistedProtectFlagRefusesUntilTheControllerJudged() = runBlocking {
        ctx.getSharedPreferences(AskZoneFence.ZONE_STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(AskZoneFence.PROTECT_KEY, true).commit()
        ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE).edit().putString(AskZoneFence.ZONES_KEY, """[{"id":"b"}]""").commit() // a flag with a zone to protect
        AskZoneFence.install(ctx)
        assertTrue(AskZoneFence.blocked())
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), service(AskZoneFence).answer("milk?", "phone_typed"))
        assertEquals(0, modelCalls.get()); assertEquals(0, askCount())
        AskZoneFence.protect(active = true, judged = false); assertTrue("still unjudged and active", AskZoneFence.blocked())
        AskZoneFence.protect(active = false, judged = true)
        assertFalse(AskZoneFence.blocked())
        assertTrue(service(AskZoneFence).answer("milk?", "phone_typed") is AskOutcome.Answered)
    }

    /** A stored `zone:` pause record (the process died inside a zone) refuses, even with no flag and no zone list. */
    @Test fun aColdStartWithAStoredZonePauseRecordRefuses() = runBlocking {
        MemoryGraph.get(ctx).db.pauseDao().upsertRecord(com.rokid.context.memory.db.PauseRecordEntity("zone:home:1", "PHONE", 1_000L, null))
        AskZoneFence.install(ctx)
        assertFalse("the records are read by prepare()", AskZoneFence.blocked())
        assertEquals(AskOutcome.Failed(AskActions.ZONE_PAUSED), service(AskZoneFence).answer("milk?", "phone_typed"))
        assertEquals(0, modelCalls.get()); assertTrue(AskZoneFence.blocked())
        // an ended record does not refuse
        AskZoneFence.resetForTests(); AskZoneFence.install(ctx)
        MemoryGraph.get(ctx).db.pauseDao().upsertRecord(com.rokid.context.memory.db.PauseRecordEntity("zone:home:1", "PHONE", 1_000L, 2_000L))
        assertTrue(service(AskZoneFence).answer("milk?", "phone_typed") is AskOutcome.Answered)
    }

    @Test fun aColdStartWithAConfiguredOrUnreadableZoneListRefusesAndADisabledOrEmptyOneDoesNot() {
        assertFalse(AskZoneFence.zonesMayBeInForce(null)); assertFalse(AskZoneFence.zonesMayBeInForce("[]"))
        assertFalse(AskZoneFence.zonesMayBeInForce("""[{"id":"a","enabled":false}]"""))
        assertTrue(AskZoneFence.zonesMayBeInForce("""[{"id":"a","enabled":false},{"id":"b"}]"""))
        assertTrue(AskZoneFence.zonesMayBeInForce("""[{"id":"a","enabled":true}]"""))
        assertTrue("unreadable = unknown = protected", AskZoneFence.zonesMayBeInForce("{broken"))
        val prefs = ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(AskZoneFence.ZONES_KEY, """[{"id":"b"}]""").commit()
        AskZoneFence.install(ctx); assertTrue(AskZoneFence.blocked()); assertNull(AskZoneFence.ticket()?.takeIf { it.isOpen() })
        AskZoneFence.protect(false, true); assertFalse(AskZoneFence.blocked())
    }

    @Test fun theControllerWordClosesTheFenceAndCancelsTicketsAtOnce() {
        AskZoneFence.install(ctx)
        val t = AskZoneFence.ticket()!!
        var fired = 0
        t.onClose { fired++ }
        AskZoneFence.protect(active = true, judged = true)
        assertEquals(1, fired); assertTrue(AskZoneFence.blocked()); assertFalse(t.isOpen())
        AskZoneFence.protect(active = false, judged = true)
        assertFalse(AskZoneFence.blocked()); assertFalse("a ticket taken before the zone stays closed", t.isOpen())
    }

    /** Codex z1 r10 P1-2: a startup read that was suspended while the controller judged clear is discarded: it must not re-hold the fence from an outdated result. */
    @Test fun aLateStartupReadAfterTheControllerClearedDoesNotReHold() = runBlocking {
        ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE).edit().putString(AskZoneFence.ZONES_KEY, """[{"id":"b"}]""").commit()
        AskZoneFence.install(ctx)
        val gate = kotlinx.coroutines.CompletableDeferred<Boolean>()
        AskZoneFence.liveRecordsReader = { gate.await() }
        val j = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { AskZoneFence.prepare() }
        kotlinx.coroutines.delay(50)
        AskZoneFence.protect(active = false, judged = true)
        assertFalse(AskZoneFence.blocked())
        gate.complete(true) // the stale result: a live record was read before the controller judged
        j.join()
        assertFalse("the late result did not re-hold", AskZoneFence.blocked()); assertFalse(AskZoneFence.fence.held)
        AskZoneFence.liveRecordsReader = { error("judged: never read again") }
        AskZoneFence.prepare()
    }

    /** A world with no enabled zone and no live zone record never leaves Ask blocked, even with a stale persisted flag and no controller (location supervision is off). Idempotent: repeated prepares agree. */
    @Test fun aWorldWithNoZonesNeverLeavesAskBlocked() = runBlocking {
        ctx.getSharedPreferences(AskZoneFence.ZONE_STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(AskZoneFence.PROTECT_KEY, true).commit()
        ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE).edit().putString(AskZoneFence.ZONES_KEY, """[{"id":"a","enabled":false}]""").commit()
        AskZoneFence.install(ctx)
        assertTrue("held at startup by the stale flag", AskZoneFence.blocked())
        AskZoneFence.liveRecordsReader = { false }
        repeat(2) { AskZoneFence.prepare(); assertFalse("round $it", AskZoneFence.blocked()) }
        assertTrue(service(AskZoneFence).answer("milk?", "phone_typed") is AskOutcome.Answered)
        // a zone record that appears later holds it again (idempotent re-reconcile)
        AskZoneFence.liveRecordsReader = { true }
        AskZoneFence.prepare(); assertTrue(AskZoneFence.blocked())
        AskZoneFence.liveRecordsReader = { false }
        AskZoneFence.prepare(); assertFalse(AskZoneFence.blocked())
    }

    /** Codex z1 r11 A P1: judged and clear, still held (the controller could not store the clear flag) and no enabled zone: prepare() keeps reconciling and releases; with a zone configured it does not. */
    @Test fun aJudgedClearHeldFenceWithNoZonesIsReleasedByPrepare() = runBlocking {
        ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE).edit().putString(AskZoneFence.ZONES_KEY, "[]").commit()
        AskZoneFence.install(ctx)
        AskZoneFence.protect(active = false, judged = true)
        AskZoneFence.fence.hold()
        AskZoneFence.prepare()
        assertFalse(AskZoneFence.fence.held); assertFalse(AskZoneFence.blocked())
        ctx.getSharedPreferences(AskZoneFence.ZONES_PREFS, Context.MODE_PRIVATE).edit().putString(AskZoneFence.ZONES_KEY, """[{"id":"a"}]""").commit()
        AskZoneFence.fence.hold(); AskZoneFence.prepare()
        assertTrue("a configured zone is not the fence's to release", AskZoneFence.fence.held)
        AskZoneFence.protect(active = true, judged = true); AskZoneFence.prepare(); assertTrue(AskZoneFence.blocked())
    }

    @Test fun theSwitchDefaultsOn() { assertTrue(AskZoneFence.isEnabled(ctx)); AskZoneFence.setEnabled(ctx, false); assertFalse(AskZoneFence.isEnabled(ctx)); AskZoneFence.setEnabled(ctx, true) }
}
