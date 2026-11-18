package com.rokid.context.orchestration.proactive

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Codex v3.1 r3 P1-3: a dismissal is durable BEFORE the card is acknowledged. A failed ledger write keeps the card hidden, pauses this process (memory), leaves a write-ahead marker in a second tiny prefs file (commit()),
 * is retried on every later store operation, and a restarted process that finds the marker pauses delivery for 24 h.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DismissalDurabilityTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_790_000_000_000L
    private val min = 60_000L
    private lateinit var disk: SharedPreferences
    private lateinit var marker: SharedPreferences
    private var failWrites = false

    @Before fun setUp() {
        disk = ctx.getSharedPreferences("test_dismiss_disk", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        marker = ctx.getSharedPreferences(PrefsSuggestionStore.MARKER_PREFS + "_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        failWrites = false
    }

    /** The ledger file whose commit() fails while [failWrites]. */
    private fun flaky(): SharedPreferences {
        val cl = SharedPreferences::class.java.classLoader
        return java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(SharedPreferences::class.java)) { _, m, a ->
            if (m.name == "edit") {
                val real = disk.edit()
                java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(SharedPreferences.Editor::class.java)) { p, em, ea ->
                    when (em.name) { "commit" -> if (failWrites) false else real.commit(); "apply" -> { real.apply(); null }; else -> { em.invoke(real, *(ea ?: emptyArray())); p } }
                }
            } else m.invoke(disk, *(a ?: emptyArray()))
        } as SharedPreferences
    }

    private fun dismissal(i: Int) = LedgerEntry(now + i * min, ProactiveFamily.PLACE_RECALL, LedgerKind.DISMISSED)
    private fun paused(s: SuggestionStore) = DeliveryLedger.pausedUntil(s.ledger()) > now + 10 * min

    @Test fun aFailedFifthDismissalSurvivesACrashAsAConservativePause() {
        val s = PrefsSuggestionStore(flaky(), marker)
        for (i in 1..4) assertTrue(s.appendLedger(dismissal(i)))
        assertFalse("four persisted: not paused", paused(s)); assertTrue("a successful write leaves no marker", marker.all.isEmpty())
        failWrites = true
        assertFalse("the fifth cannot be written", s.appendLedger(dismissal(5)))
        assertTrue("this process pauses at once (memory)", paused(s))
        assertTrue("a marker was left in the second file, committed", marker.all.isNotEmpty())
        // the process dies here: the ledger on disk holds only four dismissals; a new process opens the same two files
        failWrites = false
        val restarted = PrefsSuggestionStore(flaky(), marker)
        assertEquals("the lost fifth is unknown", 4, DeliveryLedger.decode(disk.getString(PrefsSuggestionStore.LEDGER, null)).count { it.kind == LedgerKind.DISMISSED })
        assertTrue("the restarted process pauses conservatively (DISMISS_PAUSE)", paused(restarted))
        assertEquals(Gate.DISMISS_PAUSE, ProactiveBudget().check("f2:k", ProactiveFamily.PLACE_RECALL, BudgetState(emptyList(), 0, 6, restarted.ledger()), com.rokid.context.orchestration.nudge.NudgeHud(null, com.rokid.context.core.model.Verbosity.MEDIUM, java.time.ZoneId.of("UTC")), now + 12 * min))
        // the pause is 24 h from the marked dismissal: gone after that, and the marker with it on the next write
        val later = now + 5 * min + DeliveryLedger.DISMISS_PAUSE_MS + 1
        assertFalse("24 h after the marked dismissal the pause is over", DeliveryLedger.pausedUntil(restarted.ledger()) > later)
        assertTrue(restarted.appendLedger(LedgerEntry(later, ProactiveFamily.PLACE_RECALL, LedgerKind.DELIVERED)))
        assertTrue("expired: the marker is dropped", marker.all.isEmpty()); assertFalse(DeliveryLedger.pausedUntil(PrefsSuggestionStore(flaky(), marker).ledger()) > later)
    }

    @Test fun theMarkerIsWrittenBeforeTheLedgerWriteSoACrashInBetweenIsCovered() {
        var markerSeenAtLedgerWrite = false
        val cl = SharedPreferences::class.java.classLoader
        val spying = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(SharedPreferences::class.java)) { _, m, a ->
            if (m.name == "edit") {
                val real = disk.edit()
                java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(SharedPreferences.Editor::class.java)) { p, em, ea ->
                    when (em.name) { "commit" -> { markerSeenAtLedgerWrite = marker.all.isNotEmpty(); real.commit() }; else -> { em.invoke(real, *(ea ?: emptyArray())); p } }
                }
            } else m.invoke(disk, *(a ?: emptyArray()))
        } as SharedPreferences
        assertTrue(PrefsSuggestionStore(spying, marker).appendLedger(dismissal(1)))
        assertTrue("write-ahead: the marker existed when the ledger was written", markerSeenAtLedgerWrite); assertTrue("and is gone once the line is on disk", marker.all.isEmpty())
    }

    @Test fun everyLaterStoreOperationRetriesTheFailedWriteAndClearsTheMarker() {
        val s = PrefsSuggestionStore(flaky(), marker)
        failWrites = true; assertFalse(s.appendLedger(dismissal(1))); assertTrue(marker.all.isNotEmpty())
        s.records(); assertTrue("still failing: still marked", marker.all.isNotEmpty())
        failWrites = false
        s.records() // any operation flushes
        assertEquals(1, DeliveryLedger.decode(disk.getString(PrefsSuggestionStore.LEDGER, null)).size); assertTrue(marker.all.isEmpty())
        assertEquals("not counted twice", 1, s.ledger().count { it.kind == LedgerKind.DISMISSED })
        val restarted = PrefsSuggestionStore(flaky(), marker); assertEquals(1, restarted.ledger().size)
    }

    @Test fun aFailingMarkerStoreStillLeavesTheInMemoryPause() {
        val broken = java.lang.reflect.Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, _, _ -> throw IllegalStateException("marker store down") } as SharedPreferences
        val s = PrefsSuggestionStore(flaky().also { failWrites = true }, broken)
        for (i in 1..5) assertFalse(s.appendLedger(dismissal(i)))
        assertTrue(paused(s))
    }

    @Test fun deleteAllClearsTheMarkerAndTheInheritedPause() {
        val s = PrefsSuggestionStore(flaky(), marker)
        failWrites = true; for (i in 1..5) s.appendLedger(dismissal(i)); failWrites = false
        val restarted = PrefsSuggestionStore(flaky(), marker); assertTrue(paused(restarted))
        restarted.clearAll(); assertTrue(marker.all.isEmpty()); assertFalse(paused(restarted)); assertFalse(paused(PrefsSuggestionStore(flaky(), marker)))
    }

    @Test fun withoutAMarkerFileNothingChanges() {
        val s = PrefsSuggestionStore(flaky()); for (i in 1..5) assertTrue(s.appendLedger(dismissal(i))); assertTrue(paused(s))
        assertFalse(PrefsSuggestionStore(flaky().also { disk.edit().clear().commit() }).ledger().isNotEmpty())
    }

    @Test fun theDismissalActionPersistsBeforeTheCardIsWithdrawn() {
        val order = ArrayList<String>()
        val st = object : SuggestionStore by InMemorySuggestionStore() {
            private val inner = InMemorySuggestionStore().also { it.add(SuggestionRecord("sg_1", now, "f2:k", ProactiveFamily.PLACE_RECALL, TriggerKind.PLACE_ARRIVAL, 0.9, "a", listOf("m1"), emptyList())) }
            override fun records() = inner.records()
            override fun appendLedger(e: LedgerEntry): Boolean { order += "ledger:${e.kind.token}"; return inner.appendLedger(e) }
            override fun recordFeedback(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long): Boolean { order += "feedback"; return inner.recordFeedback(action, key, family, nowMs) }
            override fun withdraw(id: String): Boolean { order += "withdraw"; return inner.withdraw(id) }
        }
        val pubs = SuggestionPublications()
        val surfaces = object : SuggestionSurfaces { override fun withdraw(ids: Collection<String>) { order += "surfaces" } }
        assertTrue(SuggestionActions({ st }, { surfaces }, { now }, pubs).dismiss("sg_1"))
        assertEquals(listOf("ledger:x", "feedback", "withdraw", "surfaces"), order); assertTrue(pubs.isWithdrawn("sg_1"))
    }
}
