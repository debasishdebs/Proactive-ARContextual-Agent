package com.rokid.context.orchestration.proactive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProactiveStoreForgetTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private fun rec(id: String, vararg src: String, ts: Long = now) = SuggestionRecord(id, ts, "f2:$id", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", src.toList(), listOf("arrived", "visits=2"))
    private fun prefs() = ctx.getSharedPreferences("test_proactive_shadow", Context.MODE_PRIVATE)

    @Before fun setUp() {
        prefs().edit().clear().commit(); ProactiveSettings.resetForTests()
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null); prefs().edit().clear().commit() }

    private fun stores(): List<SuggestionStore> = listOf(InMemorySuggestionStore(), PrefsSuggestionStore(prefs()))

    @Test fun bothStoresKeepRecordsNeverListAndCountersBounded() {
        for (s in stores()) {
            s.clearAll()
            assertTrue(s.add(rec("a", "mem:1"))); assertTrue(s.add(rec("b", "mem:2", ts = now + 1)))
            assertEquals(listOf("b", "a"), s.records().map { it.id }) // newest first
            assertEquals(1, s.withdrawWhere { it.id == "a" }); assertEquals(listOf("b"), s.records().map { it.id })
            assertTrue(s.addNever("f2:x")); assertEquals(setOf("f2:x"), s.never()); s.removeNever("f2:x"); assertTrue(s.never().isEmpty())
            s.bump("runs"); s.bump("runs"); assertEquals(2, s.counters()["runs"])
            repeat(SuggestionStore.MAX_RECORDS + 20) { i -> s.add(rec("r$i", "mem:$i", ts = now + 10 + i)) }
            assertEquals(SuggestionStore.MAX_RECORDS, s.records().size)
            s.add(rec("old", "mem:9", ts = now + 10_000_000_000L)); s.add(rec("older", "mem:9", ts = now)) // retention: older than 14 days against the newest write is trimmed
            assertFalse(s.records().any { it.id == "older" })
            repeat(SuggestionStore.MAX_NEVER) { s.addNever("k$it") }
            assertFalse(s.addNever("one-too-many"))
        }
    }

    @Test fun thePrefsStoreSurvivesAReopenAndClearAllEmptiesTheFile() {
        PrefsSuggestionStore(prefs()).apply { add(rec("a", "mem:1", "pin:user:p", "mark:m")); addNever("f2:k"); bump("would_show") }
        val again = PrefsSuggestionStore(prefs())
        assertEquals(listOf("mem:1", "pin:user:p", "mark:m"), again.records().single().sourceIds); assertEquals(setOf("f2:k"), again.never()); assertEquals(1, again.counters()["would_show"])
        again.clearAll()
        assertTrue(prefs().all.isEmpty()); assertTrue(PrefsSuggestionStore(prefs()).let { it.records().isEmpty() && it.never().isEmpty() && it.counters().isEmpty() })
    }

    @Test fun theStoredFormHoldsNoCardText() {
        PrefsSuggestionStore(prefs()).add(rec("a", "mem:1"))
        val raw = prefs().all.values.joinToString()
        assertFalse(raw.contains("last visit")); assertFalse(raw.contains("Your note"))
    }

    private fun place(id: String) = runBlocking {
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "place", 0.8, now, "Looked at X").copy(tier = MemoryTier.LONG, reviewState = ReviewState.OK))
    }

    private fun listener(s: SuggestionStore, pins: () -> Set<String>? = { emptySet() }, onDeleteAll: () -> Unit = {}) =
        ProactiveForgetListener({ s }, { SourceLiveness(ProvenanceGate.forGraph(MemoryGraph.get(ctx)), pins) }, onDeleteAll)

    @Test fun forgettingASourceWithdrawsTheSuggestionAndOnlyThatOne() = runBlocking {
        place("m1"); place("m2")
        val s = InMemorySuggestionStore(); s.add(rec("a", "mem:m1")); s.add(rec("b", "mem:m2")); s.addNever("f2:keep")
        MemoryGraph.get(ctx).memories.deleteByIds(listOf("m1"))
        listener(s).onUserForget()
        assertEquals(listOf("b"), s.records().map { it.id }); assertEquals(setOf("f2:keep"), s.never())
    }

    @Test fun aDeletedPinAndAGoneMarkWithdraw() = runBlocking {
        place("m1")
        val s = InMemorySuggestionStore(); s.add(rec("pin", "mem:m1", "pin:user:p1")); s.add(rec("mark", "mem:m1", "mark:gone")); s.add(rec("ok", "mem:m1", "pin:user:p2"))
        listener(s, pins = { setOf("user:p2") }).onFramesForgotten(listOf("f"))
        assertEquals(listOf("ok"), s.records().map { it.id })
    }

    @Test fun forgettingAnyOneOfTheDerivedSourcesWithdrawsTheRecord() = runBlocking {
        var n = 0
        // a record citing the last visit, another counted visit, the arrival memory, a pin (note possibly blank) and a mark; [kill] makes exactly one of them gone
        fun withdrawn(kill: String?): Boolean = runBlocking {
            val k = n++
            val last = "last$k"; val other = "other$k"; val arrival = "arr$k"; val mark = "mk$k"
            place(last); place(other); place(arrival)
            val livePins = if (kill == "pin") emptySet() else setOf("user:p")
            when (kill) { "last" -> MemoryGraph.get(ctx).memories.deleteByIds(listOf(last)); "other" -> MemoryGraph.get(ctx).memories.deleteByIds(listOf(other)); "arrival" -> MemoryGraph.get(ctx).memories.deleteByIds(listOf(arrival)) }
            val s = InMemorySuggestionStore()
            s.add(rec("a", "mem:$last", "mem:$other", "mem:$arrival", "pin:user:p", *(if (kill == "mark") arrayOf("mark:$mark") else emptyArray())))
            listener(s, pins = { livePins }).onUserForget()
            s.records().isEmpty()
        }
        assertFalse("nothing gone: kept", withdrawn(null))
        for (what in listOf("last", "other", "arrival", "pin", "mark")) assertTrue("forgetting $what withdraws", withdrawn(what))
    }

    @Test fun anUnreadableLivenessWithdrawsEverythingButKeepsTheNeverList() = runBlocking {
        place("m1")
        val s = InMemorySuggestionStore(); s.add(rec("a", "mem:m1")); s.addNever("f2:k")
        ProactiveForgetListener({ s }, { throw IllegalStateException("db") }).onRangeForgotten(0, 1, emptyList())
        assertTrue(s.records().isEmpty()); assertEquals(setOf("f2:k"), s.never())
    }

    @Test fun deleteAllClearsEverythingIncludingTheNeverListAndResetsTheSwitches() = runBlocking {
        val settings = ProactiveSettings.of(ctx); settings.setMaster(true); settings.setReplay(true)
        val s = PrefsSuggestionStore(prefs()); s.add(rec("a", "mem:m1")); s.addNever("f2:k"); s.bump("runs")
        listener(s, onDeleteAll = { settings.reset() }).onDeleteAll()
        assertTrue(s.records().isEmpty() && s.never().isEmpty() && s.counters().isEmpty())
        assertFalse(settings.master.value); assertFalse(settings.replay.value); assertTrue(ctx.getSharedPreferences(ProactiveSettings.PREFS, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun theSwitchesAreOffByDefault() {
        val s = ProactiveSettings.of(ctx)
        assertFalse(s.master.value); assertFalse(s.replay.value); assertFalse(s.active())
        s.setMaster(true); assertTrue(s.active()); s.setPlaceRecall(false); assertFalse(s.active())
    }

    @Test fun deleteAllAndTheInventoryKnowBothPrefsFiles() {
        assertTrue(com.rokid.context.orchestration.MemoryWipe.WIPED_PREFS.containsAll(listOf(ProactiveSettings.PREFS, PrefsSuggestionStore.PREFS)))
        val fates = com.rokid.context.memory.forget.DeletionGraph.PREFS.associate { it.name to it.fate }
        assertEquals(com.rokid.context.memory.forget.DeletionGraph.Fate.WIPED, fates[ProactiveSettings.PREFS]); assertEquals(com.rokid.context.memory.forget.DeletionGraph.Fate.WIPED, fates[PrefsSuggestionStore.PREFS])
    }

    @Test fun theLoopHasNoProviderCallNoNetworkNoNewWakeUpAndNoDelivery() {
        val dir = File("src/main/java/com/rokid/context/orchestration/proactive")
        val src = dir.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (banned in listOf("RoleRunner", "AiGraph", "ModelRouter", "okhttp", "HttpURLConnection", "AlarmManager", "WakeLock", "setExpedited", "ForegroundInfo", "requestLocationUpdates", "FusedLocation", "NotificationManager", "HudSink", "NexusHudSink", "intake", "proposedAction"))
            assertFalse("the shadow loop must not use $banned", src.contains(banned))
        assertTrue(src.contains("setRequiresBatteryNotLow(true)")); assertTrue(src.contains("ExistingWorkPolicy.KEEP"))
    }

    @Test fun triggersDoNothingWhileOffAndNeverThrowIntoTheirCaller() {
        var calls = 0
        assertFalse(ProactiveTrigger.enqueue(ctx, TriggerKind.TICK, active = { false }, enqueuer = { _, _ -> calls++ })); assertEquals(0, calls)
        assertTrue(ProactiveTrigger.enqueue(ctx, TriggerKind.PLACE_ARRIVAL, active = { true }, enqueuer = { _, _ -> calls++ })); assertEquals(1, calls)
        assertFalse(ProactiveTrigger.enqueue(ctx, TriggerKind.MOMENT_END, active = { true }, enqueuer = { _, _ -> throw IllegalStateException("wm") }))
        assertFalse(ProactiveTrigger.enqueue(ctx, TriggerKind.TICK, active = { throw IllegalStateException("prefs") }, enqueuer = { _, _ -> calls++ })); assertEquals(1, calls)
        assertEquals("proactive_loop_place_arrival", ProactiveTrigger.workName(TriggerKind.PLACE_ARRIVAL))
    }

    @Test fun theHooksAreTheExistingOnes() {
        val root = File("src/main/java/com/rokid/context")
        assertTrue(File(root, "canvas/moment/MomentCadenceWorker.kt").readText().contains("ProactiveTrigger.tick("))
        assertTrue(File(root, "work/frames/place/PlaceSamplers.kt").readText().contains("ProactiveTrigger.onPlaceArrival("))
        assertTrue(File(root, "orchestration/nudge/NudgeTrigger.kt").readText().contains("ProactiveTrigger.onMomentEnd("))
    }
}
