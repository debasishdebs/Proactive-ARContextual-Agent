package com.rokid.context.phone.zone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.phone.hub.HubPauses
import com.rokid.context.phone.hub.testDb
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex pause-zones review r3: every finding, at the controller + the real Room history + SharedPreferences that fail like Android's. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZoneR3Test {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    private val zone = PauseZone("z1", "Reserve", south = 10.0, west = 20.0, north = 10.01, east = 20.01, bufferM = 300)
    private val m = 111_194.93
    private var el = 1_000_000L
    private var wall = 1_793_000_000_000L
    private val storePrefs = FailingPrefs(ctx.getSharedPreferences("r3_zones_${System.nanoTime()}", 0))
    private val statePrefs = FailingPrefs(ctx.getSharedPreferences("r3_state_${System.nanoTime()}", 0))
    private val pauses = HubPauses(db.pauseDao()) { wall }
    private var failAdd = false
    private var failPublish = false
    private var publishes = 0
    private val sink = object : ZoneSink {
        override suspend fun activeZoneRecords() = pauses.zoneRecords()
        override suspend fun add(record: PauseRecord) { if (failAdd) error("disk full"); pauses.add(record) }
        override suspend fun end(ids: Set<String>) { if (failAdd) error("disk full"); pauses.resumeZones(ids) }
        override suspend fun publish() { if (failPublish) error("link"); publishes++ }
    }
    private val notes = object : ZoneNotifier {
        val events = ArrayList<String>()
        override fun paused(zoneNames: List<String>, locationUnknown: Boolean) { events += "paused:${zoneNames.joinToString()}:$locationUnknown" }
        override fun cleared() { events += "cleared" }
    }
    private fun controller() = ZoneController(ZoneStore(storePrefs), sink, notes, elapsed = { el }, wall = { wall }, stateStore = ZoneStateStore(statePrefs))
    private val c = controller()

    @Before fun reset() { ClockGuard.reset(); ZoneHold.set(null) }
    @After fun close() { db.close(); ZoneHold.set(null); ClockGuard.reset() }

    private fun fix(meters: Double) = ZoneFix(el, 10.0 - meters / m, 20.005, 10f)
    private suspend fun ZoneController.at(meters: Double, advance: Long = 1_000L) { el += advance; wall += advance; onFix(fix(meters)) }
    private suspend fun ZoneController.at2(meters: Double) { at(meters); at(meters) }

    // r3-1 (publish) is at the hub: see HubZoneApproachTest.aZoneChangeTheLinkDidNotAcceptIsReportedSoTheControllerRetries.

    // r3-2: an interval that is not durable keeps the memo's clear time where it was, across a restart.
    @Test fun aMissedIntervalIsNotForgottenByAClearMemoThatAdvancedWhileStoringKeptFailing() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val clear = wall
        failAdd = true; c.at(100.0); c.at(100.0)
        c.at(50_000.0); c.at(50_000.0); c.at(50_000.0); c.at(50_000.0); c.tick(); c.at(50_000.0)
        assertTrue("the interval is only in memory", ZoneHold.covers(clear + 1_000L, clear + 2_000L) && pauses.zoneRecords().isEmpty())
        assertEquals("the saved clear time did not move past the undurable interval", clear, ZoneStateStore(statePrefs).load().getValue("z1").clearWallMs)
        // The process dies here: the memory-only interval is lost, the saved state must still protect it.
        ZoneHold.set(null)
        failAdd = false
        val recovered = controller(); recovered.beginRestart()
        assertTrue(pauses.zonePausedAt(clear + 5_000L))
        recovered.start()
        assertEquals(clear, pauses.zoneRecords().single().createdAtMs)
        assertTrue(pauses.zonePausedAt(clear + 5_000L))
    }

    @Test fun onceTheIntervalIsDurableTheClearTimeAdvancesAgain() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val clear = wall
        failAdd = true; c.at(100.0); c.at(50_000.0); c.at(50_000.0); c.at(50_000.0)
        failAdd = false; c.tick() // stored as a closed interval
        assertTrue(db.pauseDao().intervals().any { it.pauseId.startsWith("zone:_missed:") })
        c.at(50_000.0)
        assertTrue(ZoneStateStore(statePrefs).load().getValue("z1").clearWallMs!! > clear)
    }

    // r3-3: the clock-rollback widening applies to the in-memory hold as well.
    @Test fun aRollbackWidensTheHoldChecksNotOnlyTheDatabaseQueries() = runTest {
        val start = wall
        ZoneHold.set(start)
        pauses.zonePausedAt(0) // observed at `start`
        wall = start - 60_000L // the clock moved back one minute
        val frame = start - 30_000L // an inside frame that maps before the hold's start
        assertTrue(pauses.zonePausedAt(frame))
        assertTrue(pauses.zoneOverlaps(frame - 1_000L, frame))
        ZoneHold.set(null); ZoneHold.retain(start, start + 10_000L, start + 10_000L)
        assertTrue("a retained closed interval too", pauses.zonePausedAt(frame)); assertTrue(pauses.zoneOverlaps(frame - 1_000L, frame))
        assertFalse("before the widening reaches", pauses.zonePausedAt(start - 120_000L))
    }

    // r3-4: a failed restore never clears protected mode (Android keeps every failed value in memory).
    @Test fun aFailedRecoveryOverACorruptListKeepsProtectionEvenWithCachedEmpty() = runTest {
        val s = ZoneStore(storePrefs)
        assertTrue(s.write(emptyList())) // the cached good list is []
        storePrefs.edit().putString("zones", "{not json").commit()
        assertTrue(s.corrupt)
        storePrefs.fail = true
        assertFalse(s.replaceAll(listOf(zone)))
        repeat(3) { assertTrue("failed recovery $it", s.corrupt) }
        assertTrue(s.corrupt)
        storePrefs.fail = false
        assertTrue(s.corrupt) // nothing durable happened yet
        assertTrue(s.replaceAll(listOf(zone)))
        assertFalse(s.corrupt); assertEquals(listOf(zone), s.zones())
    }

    @Test fun aFailedRestoreOfTheCachedListIsNotAcceptedAsCommitted() = runTest {
        val s = ZoneStore(storePrefs)
        assertTrue(s.write(listOf(zone)))
        storePrefs.fail = true
        assertFalse(s.write(listOf(zone.copy(enabled = false)))) // the commit fails, the restore fails too
        assertTrue(s.zones().single().enabled); assertFalse(s.corrupt)
        storePrefs.fail = false
        assertTrue(s.zones().single().enabled)
        assertTrue(s.write(listOf(zone.copy(enabled = false)))); assertFalse(s.zones().single().enabled)
    }

    // Codex z1 r11 A P1: a persistent prefs-write failure of the protect flag must not lock Ask out outside zones.
    private fun withStates(states: MutableList<Pair<Boolean, Boolean>>) = ZoneController(ZoneStore(storePrefs), sink, notes, elapsed = { el }, wall = { wall }, stateStore = ZoneStateStore(statePrefs), onState = { a, j -> states += a to j })

    @Test fun aPersistentFlagWriteFailureWithNoZonesDoesNotHoldAsk() = runTest {
        statePrefs.fail = true
        val states = ArrayList<Pair<Boolean, Boolean>>()
        val k = withStates(states)
        assertEquals("judged and clear at construction", false to true, states.last())
        k.start(); repeat(3) { k.tick() }
        assertTrue("never held: ${states}", states.none { it.first })
        // the persist is still retried: once the write works, the flag is stored
        statePrefs.fail = false; k.tick()
        assertEquals(false to true, states.last())
        assertFalse(statePrefs.getBoolean(ZoneStateStore.PROTECT, true))
    }

    @Test fun aFlagWriteFailureWhileEnteringAZoneStaysBlocked() = runTest {
        assertTrue(ZoneStore(storePrefs).write(listOf(zone)))
        statePrefs.fail = true
        val states = ArrayList<Pair<Boolean, Boolean>>()
        val k = withStates(states); k.start()
        repeat(4) { k.at(0.0) }
        assertEquals("inside: held", true to true, states.last())
        repeat(6) { k.tick(); assertEquals("still held (round $it)", true to true, states.last()) }
    }

    /** Zones exist and read OUTSIDE with a fresh fix, the clear flag cannot be stored: held for CLEAR_STORE_RETRIES reconciles (conservative), then released on the controller's own judgement; the persist is still retried. */
    @Test fun aClearThatCannotBeStoredInAWorldWithZonesIsBoundedNotALockout() = runTest {
        assertTrue(ZoneStore(storePrefs).write(listOf(zone)))
        statePrefs.fail = true
        val states = ArrayList<Pair<Boolean, Boolean>>()
        val k = withStates(states); k.start()
        k.at2(5_000.0)
        assertEquals("first failed store of the clear: still held", true to true, states.last())
        var released = -1
        for (i in 1..8) { k.at(5_000.0); if (!states.last().first) { released = i; break } }
        assertTrue("released within a bounded number of reconciles (was $released)", released in 1..5)
        statePrefs.fail = false; k.at(5_000.0)
        assertEquals(false to true, states.last())
        assertFalse(statePrefs.getBoolean(ZoneStateStore.PROTECT, true))
    }
}
