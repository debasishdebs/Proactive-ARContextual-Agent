package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.orchestration.AskZoneGuard
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator
import com.rokid.context.orchestration.proactive.generators.PlaceVisit
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

class RecordingSurfaces : SuggestionSurfaces {
    val hud = ArrayList<DeliveredCard>(); val nexus = ArrayList<DeliveredCard>(); val withdrawn = ArrayList<String>(); var all = 0; val surfaceOff = ArrayList<Surface>()
    var accept = true
    override fun showHud(card: DeliveredCard): Boolean { hud += card; return accept }
    override fun showNexus(card: DeliveredCard): Boolean { nexus += card; return accept }
    override fun withdraw(ids: Collection<String>) { withdrawn += ids }
    override fun withdrawAll() { all++ }
    override fun withdrawSurface(surface: Surface) { surfaceOff += surface }
}

/** v3.1: the delivery stage after the engine's commit. Every gate that closes between the engine commit and the delivery drops it; a suggestion is shown at most once per record and surface. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeliveryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 18, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private val store = InMemorySuggestionStore()
    private val history = FakeHistory()
    private val leases = Leases()
    private val gate = DataOpGate(waitMs = 50, leases = leases)
    private val surfaces = RecordingSurfaces()
    private var enabled = true; private var paused = false; private var worn = true; private var driving = false; private var nexusHost = false
    private var guard: AskZoneGuard = AskZoneGuard { false }
    private var hud: NudgeHud? = NudgeHud(null, Verbosity.MEDIUM, zone)
    private var cap = 6; private var nudgesToday = 0; private var anchorBlocked = false; private var pinsLive = true
    private var wantPhone = true; private var wantHud = false; private var wantNexus = false

    private fun visit(id: String, ts: Long) = PlaceVisit(id, null, "wd:Q1", "Golconda", null, ts, 0.8)

    @Before fun setUp() = runBlocking {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        for (id in listOf("m_now", "m_old")) MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "place", 0.8, now, "Looked at Golconda").copy(tier = MemoryTier.LONG, reviewState = ReviewState.OK))
        history.visits = listOf(visit("m_now", now - 60_000), visit("m_old", now - 6 * DAY))
    }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun seams() = ProactiveSeams(
        enabled = { enabled }, paused = { paused }, zoneGuard = object : AskZoneGuard { override fun blocked() = guard.blocked() }, worn = { worn }, driving = { driving }, hud = { hud }, nexusHost = { nexusHost },
        globalCap = { cap }, globalToday = { nudgesToday }, anchorBlocked = { anchorBlocked }, pinsLive = { pinsLive },
    )
    private fun wanted(s: Surface) = enabled && when (s) { Surface.PHONE -> wantPhone; Surface.HUD -> wantHud; Surface.NEXUS -> wantNexus }
    private fun gen() = PlaceRecallGenerator(history) { zone }

    private fun engine(): ProactiveEngine { val mem = MemoryGraph.get(ctx); return ProactiveEngine(mem.db, ProvenanceGate.forGraph(mem), store, listOf(gen()), seams(), gate, clock = { now }) }
    private fun delivery(): ProactiveDelivery { val mem = MemoryGraph.get(ctx); val g = gen(); return ProactiveDelivery(mem.db, ProvenanceGate.forGraph(mem), store, { r, t -> g.render(r, t) }, seams(), ::wanted, { surfaces }, gate, clock = { now }) }

    private fun committed(): SuggestionRecord = runBlocking { val run = engine().run(TriggerKind.PLACE_ARRIVAL); assertEquals(Gate.WOULD_SHOW, run.gate); run.record!! }
    private fun assertDropped(g: Gate, out: Map<Surface, Gate>, vararg s: Surface) {
        for (x in s) assertEquals("$x", g, out[x])
        assertTrue("nothing was shown", surfaces.hud.isEmpty() && surfaces.nexus.isEmpty())
        assertTrue("nothing was claimed", store.records().all { it.delivered.isEmpty() })
    }

    // ---- flags ---------------------------------------------------------------------------------------------------------------------------------------

    @Test fun everySurfaceSwitchIsOffOnAFreshInstallAndNeedsTheMaster() {
        val prefs = ctx.getSharedPreferences("test_delivery_flags", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val s = ProactiveSettings(prefs)
        for (x in Surface.entries) assertFalse(s.deliversTo(x))
        assertFalse(s.deliverPhone.value || s.deliverHud.value || s.deliverNexus.value)
        s.setDeliver(Surface.HUD, true); assertFalse("the master is still off", s.deliversTo(Surface.HUD))
        s.setMaster(true); assertTrue(s.deliversTo(Surface.HUD)); assertFalse("independent", s.deliversTo(Surface.PHONE)); assertFalse(s.deliversTo(Surface.NEXUS))
        s.setPlaceRecall(false); assertFalse("the family switch too", s.deliversTo(Surface.HUD)); s.setPlaceRecall(true)
        s.reset(); for (x in Surface.entries) assertFalse(s.deliversTo(x)); assertFalse(prefs.getBoolean("deliver_hud", false))
    }

    @Test fun withEverySwitchOffDeliveryDoesNothingAtAll() = runBlocking {
        val r = committed(); wantPhone = false
        assertTrue(delivery().deliver(r.id, now).isEmpty()); assertTrue(store.records().single().delivered.isEmpty()); assertNull(store.counters()["deliver_phone"])
    }
    private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)

    // ---- the passing path ----------------------------------------------------------------------------------------------------------------------------

    @Test fun phoneDeliveryClaimsTheRecordAndRendersNothingElse() = runBlocking {
        val r = committed()
        assertEquals(mapOf(Surface.PHONE to Gate.DELIVERED), delivery().deliver(r.id, now))
        assertEquals(setOf("phone"), store.records().single().delivered); assertEquals(1, store.counters()["deliver_phone"]); assertTrue(surfaces.hud.isEmpty() && surfaces.nexus.isEmpty())
        assertEquals("the lease is closed", 0, leases.openCount)
    }

    @Test fun theHudCardIsATemplateOfTheLiveSourcesWithASourceLabel() = runBlocking {
        wantHud = true; val r = committed()
        val out = delivery().deliver(r.id, now)
        assertEquals(Gate.DELIVERED, out[Surface.HUD]); val c = surfaces.hud.single()
        assertEquals(r.id, c.id); assertEquals("Golconda", c.title); assertTrue(c.line.startsWith("last visit")); assertTrue(c.sourceLabel.startsWith("from ") && c.sourceLabel.contains("·"))
        assertTrue(c.sources.containsAll(setOf("m_now", "m_old")))
    }

    @Test fun nexusNeedsTheNexusHostAndTheHudNeedsNot() = runBlocking {
        wantHud = true; wantNexus = true; val r = committed()
        var out = delivery().deliver(r.id, now)
        assertEquals(Gate.SURFACE_OFF, out[Surface.NEXUS]); assertEquals(Gate.DELIVERED, out[Surface.HUD]); assertTrue(surfaces.nexus.isEmpty())
        // the host is Nexus: its card goes there, and the PCA glasses HUD does not draw a second one
        val r2 = store.records().single(); store.withdraw(r2.id); store.add(r2.copy(id = "sg_2", delivered = emptySet(), key = "f2:other"))
        nexusHost = true; out = delivery().deliver("sg_2", now)
        assertEquals(Gate.DELIVERED, out[Surface.NEXUS]); assertEquals(Gate.SURFACE_OFF, out[Surface.HUD]); assertEquals(1, surfaces.nexus.size); assertEquals(1, surfaces.hud.size)
    }

    @Test fun aSuggestionIsShownAtMostOncePerRecordAndSurface() = runBlocking {
        wantHud = true; val r = committed()
        val d = delivery()
        assertEquals(Gate.DELIVERED, d.deliver(r.id, now)[Surface.HUD])
        val again = d.deliver(r.id, now)
        assertEquals(Gate.ALREADY_DELIVERED, again[Surface.HUD]); assertEquals(Gate.ALREADY_DELIVERED, again[Surface.PHONE])
        assertEquals(1, surfaces.hud.size); assertEquals(setOf("phone", "hud"), store.records().single().delivered)
        // a racing second delivery claims nothing more
        val racers = (1..4).map { async { d.deliver(r.id, now) } }; racers.forEach { it.await() }
        assertEquals(1, surfaces.hud.size)
    }

    // ---- the gate matrix: each gate closing between the engine commit and the delivery -----------------------------------------------------------------

    @Test fun masterSwitchOffDeliversNothing() = runBlocking { val r = committed(); wantHud = true; enabled = false; assertTrue(delivery().deliver(r.id, now).isEmpty()); assertTrue(surfaces.hud.isEmpty()) }
    @Test fun surfaceSwitchOff() = runBlocking { val r = committed(); wantHud = true; val d = delivery(); wantHud = false; wantPhone = true; val out = d.deliver(r.id, now); assertFalse(Surface.HUD in out) }
    @Test fun pausedDrops() = runBlocking { wantHud = true; val r = committed(); paused = true; assertDropped(Gate.PAUSE, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun aZoneFenceThatClosedDrops() = runBlocking { wantHud = true; val r = committed(); guard = AskZoneGuard { true }; assertDropped(Gate.ZONE, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun aPlaceInsideAZoneDrops() = runBlocking { wantHud = true; val r = committed(); anchorBlocked = true; assertDropped(Gate.ZONE_ANCHOR, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun drivingDropsEverySurface() = runBlocking { wantHud = true; val r = committed(); driving = true; assertDropped(Gate.DRIVING, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun quietHoursAlwaysWin() = runBlocking { wantHud = true; val r = committed(); hud = NudgeHud(QuietHours(11 * 60, 13 * 60), Verbosity.MEDIUM, zone); assertDropped(Gate.QUIET_HOURS, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun theNudgeCapIsReCheckedAndTheRecordItselfIsNotCounted() = runBlocking {
        wantHud = true; val r = committed(); cap = 1
        assertEquals("one slot is enough for the record's own delivery", Gate.DELIVERED, delivery().deliver(r.id, now)[Surface.PHONE])
    }
    @Test fun theBudgetClosedBeforeDelivery() = runBlocking { wantHud = true; val r = committed(); nudgesToday = 6; assertDropped(Gate.GLOBAL_CAP, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun theNeverListDrops() = runBlocking { wantHud = true; val r = committed(); store.addNever(r.key); assertDropped(Gate.FEEDBACK, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun aSnoozedKeyDrops() = runBlocking { wantHud = true; val r = committed(); store.recordFeedback(FeedbackAction.SNOOZE, r.key, r.family, now); assertDropped(Gate.FEEDBACK, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun notWornDropsHudAndNexusButNotThePhone() = runBlocking {
        wantHud = true; val r = committed(); worn = false
        val out = delivery().deliver(r.id, now)
        assertEquals(Gate.WEAR, out[Surface.HUD]); assertEquals(Gate.DELIVERED, out[Surface.PHONE]); assertTrue(surfaces.hud.isEmpty())
        nexusHost = true; wantNexus = true; store.withdraw(r.id); store.add(r.copy(id = "sg_n", delivered = emptySet(), key = "f2:n"))
        assertEquals(Gate.WEAR, delivery().deliver("sg_n", now)[Surface.NEXUS]); assertTrue(surfaces.nexus.isEmpty())
    }
    @Test fun silentVerbosityNeverShowsOnTheHud() = runBlocking { wantHud = true; val r = committed(); hud = NudgeHud(null, Verbosity.SILENT, zone); val out = delivery().deliver(r.id, now); assertEquals(Gate.SURFACE_OFF, out[Surface.HUD]); assertTrue(surfaces.hud.isEmpty()) }
    @Test fun maxVerbosityShowsOnTheHud() = runBlocking { wantHud = true; val r = committed(); hud = NudgeHud(null, Verbosity.MAX, zone); assertEquals(Gate.DELIVERED, delivery().deliver(r.id, now)[Surface.HUD]) }
    @Test fun aForgottenSourceDropsAtDelivery() = runBlocking {
        wantHud = true; val r = committed(); MemoryGraph.get(ctx).memories.deleteByIds(listOf("m_old"))
        val out = delivery().deliver(r.id, now); assertTrue(out.values.all { it == Gate.PROVENANCE || it == Gate.LEASE }); assertTrue(surfaces.hud.isEmpty()); assertTrue(store.records().all { it.delivered.isEmpty() })
    }
    @Test fun aRemovedPinDrops() = runBlocking { wantHud = true; val r = committed(); pinsLive = false; history.pins = listOf(com.rokid.context.orchestration.proactive.generators.PinInfo("user:p", "P", "n")); store.withdraw(r.id); store.add(r.copy(sourceIds = r.sourceIds + "pin:user:p")); assertDropped(Gate.PROVENANCE, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun aDeleteAllAfterTheCommitDrops() = runBlocking { wantHud = true; val r = committed(); store.clearAll(); assertDropped(Gate.WITHDRAWN, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun aWithdrawnRecordIsNeverDelivered() = runBlocking { wantHud = true; val r = committed(); store.withdraw(r.id); assertDropped(Gate.WITHDRAWN, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun noHudSettingsFailsClosed() = runBlocking { wantHud = true; val r = committed(); hud = null; assertDropped(Gate.OFF, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD) }
    @Test fun aForgetHoldingTheGateBlocksTheLease() = runBlocking {
        wantHud = true; val r = committed(); val release = CompletableDeferred<Unit>(); val deleting = async { gate.delete { release.await() } }; yield()
        assertDropped(Gate.LEASE, delivery().deliver(r.id, now), Surface.PHONE, Surface.HUD); release.complete(Unit); deleting.await()
    }

    // ---- gates closing DURING the delivery (after the last suspending read, before the final step) ----------------------------------------------------------

    private fun closing(what: () -> Unit, g: Gate) = runBlocking {
        wantHud = true; val r = committed(); val d = delivery(); d.testBeforeFinal = { what() }
        assertDropped(g, d.deliver(r.id, now), Surface.PHONE, Surface.HUD)
    }
    @Test fun aZoneStartingJustBeforeTheFinalStep() = closing({ guard = AskZoneGuard { true } }, Gate.ZONE)
    @Test fun drivingStartingJustBeforeTheFinalStep() = closing({ driving = true }, Gate.DRIVING)
    @Test fun theNeverListJustBeforeTheFinalStep() = closing({ store.addNever("f2:wd:Q1") }, Gate.FEEDBACK)
    @Test fun aDismissalJustBeforeTheFinalStep() = closing({ store.withdraw(store.records().single().id) }, Gate.WITHDRAWN)
    @Test fun aSwitchTurnedOffJustBeforeTheFinalStepDropsThatSurfaceOnly() = runBlocking {
        wantHud = true; val r = committed(); val d = delivery(); d.testBeforeFinal = { wantHud = false }
        val out = d.deliver(r.id, now); assertEquals(Gate.SURFACE_OFF, out[Surface.HUD]); assertEquals(Gate.DELIVERED, out[Surface.PHONE]); assertTrue(surfaces.hud.isEmpty())
    }
    @Test fun notWornJustBeforeTheFinalStep() = runBlocking {
        wantHud = true; val r = committed(); val d = delivery(); d.testBeforeFinal = { worn = false }
        assertEquals(Gate.WEAR, d.deliver(r.id, now)[Surface.HUD]); assertTrue(surfaces.hud.isEmpty())
    }
    @Test fun aFailingSurfaceStillConsumesTheClaim() = runBlocking {
        wantHud = true; surfaces.accept = false; val r = committed(); val d = delivery()
        assertEquals(Gate.COMMIT, d.deliver(r.id, now)[Surface.HUD]); surfaces.accept = true
        assertEquals(Gate.ALREADY_DELIVERED, d.deliver(r.id, now)[Surface.HUD]); assertEquals(1, surfaces.hud.size)
    }

    // ---- withdrawal everywhere --------------------------------------------------------------------------------------------------------------------------

    @Test fun dismissWritesFeedbackAndWithdrawsFromEverySurface() = runBlocking {
        wantHud = true; val r = committed(); delivery().deliver(r.id, now)
        val a = SuggestionActions({ store }, { surfaces }, { now })
        assertTrue(a.dismiss(r.id))
        assertTrue(store.records().isEmpty()); assertEquals(listOf(r.id), surfaces.withdrawn)
        assertEquals(1, store.feedback().keys[r.key]!!.dismissCount); assertEquals(1, store.counters()["fb_dismiss"])
        assertEquals(Gate.WITHDRAWN, delivery().deliver(r.id, now)[Surface.PHONE])
        assertFalse(a.dismiss(r.id))
    }
    @Test fun snoozeBlocksTheKeyForADayThenItComesBack() = runBlocking {
        val r = committed(); val a = SuggestionActions({ store }, { surfaces }, { now })
        assertTrue(a.snooze(r.id)); assertTrue(store.records().isEmpty()); assertEquals(listOf(r.id), surfaces.withdrawn)
        assertEquals(Gate.FEEDBACK, engine().run(TriggerKind.TICK, now).gate)
        val later = now + FeedbackMath.SNOOZE_MS + 60_000; history.visits = listOf(visit("m_now", later - 60_000), visit("m_old", now - 6 * DAY))
        assertEquals(Gate.WOULD_SHOW, engine().run(TriggerKind.TICK, later).gate)
    }
    @Test fun neverIsKeyBasedAndWithdrawsEverywhere() = runBlocking {
        val r = committed(); val a = SuggestionActions({ store }, { surfaces }, { now })
        assertTrue(a.never(r.id)); assertEquals(setOf(r.key), store.never()); assertEquals(listOf(r.id), surfaces.withdrawn)
        assertEquals(Gate.FEEDBACK, engine().run(TriggerKind.TICK).gate)
        store.clearAll(); assertTrue("Delete all clears the never list too (ruling 6)", store.never().isEmpty())
    }
    @Test fun thumbsKeepTheCardAndMoveTheCounters() = runBlocking {
        val r = committed(); val a = SuggestionActions({ store }, { surfaces }, { now })
        assertTrue(a.thumb(r.id, true)); assertEquals(1, store.feedback().keys[r.key]!!.vote); assertEquals(1, store.feedback().families[r.family]!!.up)
        assertTrue(a.thumb(r.id, false)); assertEquals(-1, store.feedback().keys[r.key]!!.vote); assertEquals(0, store.feedback().families[r.family]!!.up); assertEquals(1, store.feedback().families[r.family]!!.down)
        assertEquals("a thumb withdraws nothing", 1, store.records().size); assertTrue(surfaces.withdrawn.isEmpty())
    }

    @Test fun forgettingASourceWithdrawsFromEverySurfaceAndDeleteAllClearsAndWithdrawsAll() = runBlocking {
        wantHud = true; val r = committed(); delivery().deliver(r.id, now)
        val pins = { emptySet<String>() as Set<String>? }
        val l = ProactiveForgetListener({ store }, { SourceLiveness(ProvenanceGate.forGraph(MemoryGraph.get(ctx)), pins) }, surfaces = { surfaces })
        MemoryGraph.get(ctx).memories.deleteByIds(listOf("m_old"))
        l.onUserForget()
        assertTrue(store.records().isEmpty()); assertEquals(listOf(r.id), surfaces.withdrawn)
        store.add(r.copy(id = "sg_x", sourceIds = listOf("mem:m_now"))); l.onDeleteAll()
        assertTrue(store.records().isEmpty()); assertEquals(1, surfaces.all)
    }

    @Test fun deliveredStateAndFeedbackSurviveAReopenAndAreClearedByDeleteAll() {
        val prefs = ctx.getSharedPreferences("test_delivery_store", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        val s = PrefsSuggestionStore(prefs); val rec = SuggestionRecord("sg_1", now, "f2:k", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", listOf("mem:1"), listOf("arrived"))
        s.add(rec); assertTrue(s.markDelivered("sg_1", Surface.PHONE)); assertFalse(s.markDelivered("sg_1", Surface.PHONE)); assertFalse(s.markDelivered("nope", Surface.HUD))
        s.recordFeedback(FeedbackAction.DOWN, "f2:k", ProactiveFamily.PLACE_RECALL, now); s.recordFeedback(FeedbackAction.DISMISS, "f2:k", ProactiveFamily.PLACE_RECALL, now)
        val again = PrefsSuggestionStore(prefs)
        assertEquals(setOf("phone"), again.records().single().delivered); assertEquals(-1, again.feedback().keys["f2:k"]!!.vote); assertEquals(1, again.counters()["fb_down"])
        assertFalse("no text in the stored form", prefs.all.values.joinToString().contains("last visit"))
        again.resetFeedback(); assertTrue(again.feedback().keys.isEmpty()); assertEquals("Reset learning leaves the records", 1, again.records().size)
        again.recordFeedback(FeedbackAction.UP, "f2:k", ProactiveFamily.PLACE_RECALL, now); again.clearAll(); assertTrue(prefs.all.isEmpty())
    }
}
