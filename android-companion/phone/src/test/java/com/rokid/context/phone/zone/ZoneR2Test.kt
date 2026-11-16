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

/** Codex pause-zones review r2: every finding, at the controller + the real Room history + SharedPreferences that fail like Android's. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZoneR2Test {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    private val zone = PauseZone("z1", "Reserve", south = 10.0, west = 20.0, north = 10.01, east = 20.01, bufferM = 300)
    private val m = 111_194.93
    private var el = 1_000_000L
    private var wall = 1_793_000_000_000L
    private val storePrefs = FailingPrefs(ctx.getSharedPreferences("r2_zones_${System.nanoTime()}", 0))
    private val statePrefs = FailingPrefs(ctx.getSharedPreferences("r2_state_${System.nanoTime()}", 0))
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

    // P1-2: the restart barrier.
    @Test fun aRestartRaisesTheBarrierAtOnceSoNothingIsAdmittedBeforeNoFixProtectionExists() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val lastClear = wall
        assertFalse(ZoneHold.held)
        c.beginRestart() // the hub restarts: reconciliation has not run
        assertTrue(ZoneHold.held); assertTrue(c.enforcing); assertTrue("the glasses are told to enforce, whatever pause list they sync", c.approach)
        assertTrue("the outage is dropped before any record exists", pauses.zonePausedAt(lastClear + 5_000L)); assertTrue(pauses.pausedNow())
        assertFalse("frames from before the last clear fix are fine", pauses.zonePausedAt(lastClear - 5_000L))
        assertTrue(pauses.zoneRecords().isEmpty())
        el += 600_000L; wall += 600_000L
        c.start()
        assertEquals(lastClear, pauses.zoneRecords().single().createdAtMs); assertFalse("the record replaced the barrier", ZoneHold.held)
        assertTrue(pauses.zonePausedAt(lastClear + 5_000L))
    }

    @Test fun aRestartWithNoKnownClearTimeProtectsTheWholeHistory() = runTest {
        c.upsert(zone); c.start()
        val fresh = controller() // a new process: nothing saved yet by a fix
        statePrefs.edit().clear().commit()
        fresh.beginRestart()
        assertTrue(pauses.zonePausedAt(wall - 3 * 24 * 3_600_000L))
    }

    // P1-3: an interval whose record was never stored is retained after the exit.
    @Test fun aMissedIntervalStaysEnforcedAfterTheExitEvenIfStoringKeepsFailing() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val clear = wall
        failAdd = true; c.at(100.0); val insideAt = wall + 1_000L
        assertTrue(ZoneHold.held)
        c.at(50_000.0); c.at(50_000.0); c.at(50_000.0) // two qualifying exit fixes: the zone reads clear again
        assertFalse("the open hold ended with the exit", ZoneHold.held)
        assertTrue("a buffered frame from inside the zone is still dropped", pauses.zonePausedAt(insideAt))
        assertTrue(pauses.zoneOverlaps(insideAt, insideAt + 1_000L))
        assertFalse("a frame from before the interval passes", pauses.zonePausedAt(clear - 5_000L))
        failAdd = false; c.tick()
        assertTrue("it is stored as a closed interval once the store works", pauses.zoneRecords().isEmpty())
        val iv = db.pauseDao().intervals().single { it.pauseId.startsWith("zone:_missed:") }
        assertEquals(clear, iv.startMs); assertTrue(iv.effectiveEndMs!! >= insideAt)
    }

    // P1-4: the clock guard is continuous (elapsed-time referenced) and its commit failures fail closed.
    @Test fun aRollbackAfterTheClockHadAdvancedPastThePeakIsStillDetected() {
        var e = 5_000_000L
        ClockGuard.elapsedSource = { e }
        ClockGuard.observe(1_000_000L) // entry observed at 1000
        e += 600_000L // the clock advanced to 1600 (never observed), then rolled back to 1100
        ClockGuard.observe(1_100_000L)
        assertEquals("1100 is above the recorded peak 1000, yet the clock lost 500", 500_000L, ClockGuard.widenMs())
        e += 60_000L; ClockGuard.observe(1_160_000L); assertEquals("steady time adds nothing", 500_000L, ClockGuard.widenMs())
    }

    @Test fun anInsideFrameThatShiftsBelowTheIntervalStartIsDroppedAfterTheRollbackThePeakNeverSaw() = runTest {
        ClockGuard.elapsedSource = { el }
        c.upsert(zone); c.start(); c.at2(50_000.0); c.at(100.0)
        val start = pauses.zoneRecords().single().createdAtMs
        val insideOld = wall + 500L
        el += 600_000L; wall += 600_000L // the clock advanced (never observed) ...
        wall -= 500_000L // ... and rolled back to just above the recorded peak
        val mapped = insideOld - 500_000L // the early buffered inside frame, mapped with the re-measured offset
        assertTrue(mapped < start)
        assertTrue("the first look after the rollback widens the window", pauses.zonePausedAt(mapped))
    }

    @Test fun aClockStateThatCannotBeStoredWidensToTheWholeHistoryUntilItIsStored() {
        var ok = false
        ClockGuard.restore(0, 0, 0) { _, _, _ -> ok }
        ClockGuard.observe(1_000_000L)
        assertEquals(7 * 24 * 3_600_000L, ClockGuard.widenMs())
        ok = true; ClockGuard.observe(1_001_000L)
        assertEquals(0L, ClockGuard.widenMs())
    }

    @Test fun theControllerSamplesTheClockOnEveryTickNotOnlyAtAdmission() = runTest {
        ClockGuard.elapsedSource = { el }
        c.upsert(zone); c.start(); c.at2(50_000.0)
        el += 600_000L; wall += 600_000L; c.tick()
        el += 1_000L; wall -= 400_000L; c.tick() // rolled back 400 s from where it should be, seen by the tick alone
        assertTrue(ClockGuard.widenMs() >= 400_000L)
    }

    // P1-5: configuration persistence.
    @Test fun aCommitThatFailedIsNotTheStoredListEvenThoughThePreferencesKeepItInMemory() = runTest {
        val s = ZoneStore(storePrefs)
        assertTrue(s.write(listOf(zone)))
        storePrefs.fail = true
        assertFalse(s.write(listOf(zone.copy(enabled = false))))
        assertTrue("the next read does not adopt the uncommitted value", s.zones().single().enabled)
        assertTrue(ZoneStore(storePrefs).zones().let { true }) // another instance over the same prefs object must not crash
        storePrefs.fail = false
        assertTrue(s.zones().single().enabled)
        assertTrue(s.write(listOf(zone.copy(enabled = false)))); assertFalse(s.zones().single().enabled)
    }

    @Test fun aZoneThatCouldNotBeDisabledStaysEnforcedThroughTheControllerToo() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0)
        storePrefs.fail = true
        assertEquals(ZoneChange.STORE_FAILED, c.setEnabled("z1", false))
        c.tick(); c.at(100.0); c.at(100.0)
        assertTrue("the zone is still enabled and judged (the failing disk also holds the record in memory)", c.zones().single().enabled && ZoneHold.held && pauses.zonePausedAt(wall))
    }

    @Test fun validJsonWithAnInvalidZoneIsUnreadableNotAnUnprotectedEmptyList() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0)
        val bad = listOf(zone, zone.copy(id = "z2", south = 50.0)) // south > north
        storePrefs.edit().putString("zones", Wire.json.encodeToString(ListSerializer(PauseZone.serializer()), bad)).commit()
        c.tick()
        assertTrue(ZoneStore(storePrefs).corrupt); assertTrue(c.enforcing); assertTrue(c.snapshot.value.protectedMode)
        assertTrue(pauses.zoneRecords().any { it.pauseId.startsWith("zone:_unreadable:") })
        assertTrue(notes.events.last().startsWith("paused:") && notes.events.last().contains("unreadable"))
        assertEquals(listOf(zone), c.zones()) // the last good list is kept for display
        val onlyInvalid = Wire.json.encodeToString(ListSerializer(PauseZone.serializer()), listOf(zone.copy(south = 50.0)))
        storePrefs.edit().putString("zones", onlyInvalid).commit(); c.tick()
        assertTrue("an all-invalid list is not 'no zones'", c.enforcing)
    }

    // P1-6: missing recovery state is unknown.
    @Test fun aZoneWithNoSavedClearTimeIsProtectedFromAConservativePastNotFromNow() = runTest {
        assertTrue(ZoneStore(storePrefs).write(listOf(zone))) // saved zone, no state: the process died before the first successful state save
        val recovered = controller(); recovered.start()
        val rec = pauses.zoneRecords().single()
        assertTrue("starts at least a day back, not at recovery", rec.createdAtMs <= wall - 24 * 3_600_000L)
        assertTrue(pauses.zonePausedAt(wall - 3_600_000L))
    }

    @Test fun aStateThatCouldNotBeSavedIsStillUnknownAfterTheProcessDies() = runTest {
        statePrefs.fail = true
        c.upsert(zone); c.start(); c.at2(50_000.0) // every state save failed (commit false, as on a full disk)
        statePrefs.edit().clear().commit()
        val recovered = controller(); recovered.start()
        assertTrue(pauses.zoneRecords().single().createdAtMs <= wall - 24 * 3_600_000L)
    }

    @Test fun aZoneCreatedNowHasNoPastToProtect() = runTest {
        c.upsert(zone); c.start(); val t = wall
        assertEquals(t, pauses.zoneRecords().single().createdAtMs)
    }

    // P1-7: a failed publication is retried.
    @Test fun aFailedPublicationIsRetriedByTheNextTickUntilItGoesOut() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0)
        failPublish = true; val before = publishes
        c.at(100.0) // entry: the glasses must be told
        assertEquals(before, publishes)
        c.tick(); assertEquals("still failing, still pending", before, publishes)
        failPublish = false; c.tick()
        assertEquals("the retry went out", before + 1, publishes)
        c.tick(); assertEquals("and only once", before + 1, publishes)
    }

    // P2-8: bulk replacement obeys the single-edit guards.
    @Test fun replaceAllCannotEndProtectionWhileInsideOrBeforeTheZonesAreJudged() = runTest {
        c.upsert(zone)
        val fresh = controller()
        assertEquals(ZoneChange.NOT_READY, fresh.replaceAll(emptyList()))
        fresh.start(); fresh.at(100.0); fresh.at(100.0)
        assertEquals(ZoneChange.BLOCKED_INSIDE, fresh.replaceAll(emptyList()))
        assertEquals(ZoneChange.BLOCKED_INSIDE, fresh.replaceAll(listOf(zone.copy(enabled = false))))
        assertEquals(listOf(zone), fresh.zones())
        assertEquals(ZoneChange.INVALID, fresh.replaceAll(listOf(zone.copy(south = 50.0))))
        assertEquals("an identical list is fine", ZoneChange.OK, fresh.replaceAll(listOf(zone)))
        fresh.at(50_000.0); fresh.at(50_000.0)
        assertEquals(ZoneChange.OK, fresh.replaceAll(emptyList())); assertTrue(fresh.zones().isEmpty())
    }
}
