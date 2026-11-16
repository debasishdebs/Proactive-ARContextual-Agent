package com.rokid.context.phone.zone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.core.model.zone.ZoneState
import com.rokid.context.link.Admission
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.phone.hub.HubPauses
import com.rokid.context.phone.hub.testDb
import kotlinx.coroutines.test.runTest
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

/** Codex pause-zones review r1: every probe, at the controller + the real Room history + the real FrameAdmission. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZoneR1Test {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    private val zone = PauseZone("z1", "Reserve", south = 10.0, west = 20.0, north = 10.01, east = 20.01, bufferM = 300)
    private val m = 111_194.93
    private var el = 1_000_000L
    private var wall = 1_793_000_000_000L
    private val storePrefs = ctx.getSharedPreferences("r1_zones_${System.nanoTime()}", 0)
    private val statePrefs = ctx.getSharedPreferences("r1_state_${System.nanoTime()}", 0)
    private val pauses = HubPauses(db.pauseDao()) { wall }
    private val logs = ArrayList<String>()
    private var failAdd = false
    private var publishes = 0
    private val sink = object : ZoneSink {
        override suspend fun activeZoneRecords() = pauses.zoneRecords()
        override suspend fun add(record: PauseRecord) { if (failAdd) error("disk full"); pauses.add(record) }
        override suspend fun end(ids: Set<String>) = pauses.resumeZones(ids)
        override suspend fun publish() { publishes++ }
    }
    private val notes = object : ZoneNotifier {
        val events = ArrayList<String>()
        override fun paused(zoneNames: List<String>, locationUnknown: Boolean) { events += "paused:${zoneNames.joinToString()}:$locationUnknown" }
        override fun cleared() { events += "cleared" }
    }
    private fun controller(prefs: android.content.SharedPreferences = storePrefs, state: android.content.SharedPreferences = statePrefs) =
        ZoneController(ZoneStore(prefs), sink, notes, elapsed = { el }, wall = { wall }, log = { logs += it }, stateStore = ZoneStateStore(state))
    private val c = controller()

    @Before fun reset() { ClockGuard.reset(); ZoneHold.set(null) }
    @After fun close() { db.close(); ZoneHold.set(null); ClockGuard.reset() }

    private fun fix(meters: Double, acc: Float = 10f) = ZoneFix(el, 10.0 - meters / m, 20.005, acc)
    private suspend fun ZoneController.at(meters: Double, acc: Float = 10f, advance: Long = 1_000L) { el += advance; wall += advance; onFix(fix(meters, acc)) }
    private suspend fun ZoneController.at2(meters: Double) { at(meters); at(meters) }
    private fun admission() = FrameAdmission(ReceivedFrameLedger(File(java.nio.file.Files.createTempDirectory("r1").toFile(), "l.txt")), pauses)
    private fun meta(id: String, at: Long) = FrameMeta(frameId = id, chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = at)

    // P1-4 + P1-5: the uncertainty window.
    @Test fun aRecoveryAfterProcessDeathBackdatesToTheLastTrustedClearFixAndDropsTheOutageFrames() = runTest {
        val t0 = wall
        c.upsert(zone); c.start(); c.at2(50_000.0)
        val lastClear = wall
        // the phone dies; the glasses walk into the zone offline and buffer frames; the phone recovers 20 minutes later (new process: same prefs, new controller)
        el += 20 * 60_000L; wall += 20 * 60_000L
        val recovered = controller(); recovered.start()
        val rec = pauses.zoneRecords().single()
        assertEquals("the interval starts at the last time a fix proved us clear", lastClear, rec.createdAtMs)
        val a = admission()
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), a.decide(meta("in", lastClear + 10 * 60_000L), ClockOffset(0), wall))
        assertTrue("a frame from before the last clear fix is fine", a.decide(meta("pre", t0 - 1_000L), ClockOffset(0), wall) is Admission.Ingest)
    }

    @Test fun aRestartInASurvivingProcessJudgesTheZonesAgainFromNothing() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val lastClear = wall
        assertTrue(pauses.zoneRecords().isEmpty()); assertFalse(c.approach)
        c.beginRestart(); assertTrue("fail closed at once", c.approach)
        el += 600_000; wall += 600_000
        c.start() // Hub.start: stop + start in the same process
        assertEquals(lastClear, pauses.zoneRecords().single().createdAtMs); assertEquals(ZoneState.INSIDE, c.snapshot.value.evaluation.state("z1"))
        c.at(50_000.0); assertEquals("one fix is not the exit", 1, pauses.zoneRecords().size); c.at(50_000.0); assertTrue(pauses.zoneRecords().isEmpty())
    }

    @Test fun anOrdinaryEntryStartsAtTheLastNonInsideFixNotAtTheInsideFix() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val clear = wall
        c.at(100.0, advance = 240_000L) // far at the last fix, inside 4 minutes later
        assertEquals(clear, pauses.zoneRecords().single().createdAtMs)
    }

    // P1-8: persistence failure.
    @Test fun aRecordThatCannotBeStoredIsHeldInMemoryAndTheFramesAreStillDropped() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); val clear = wall
        failAdd = true; c.at(100.0)
        assertTrue(pauses.zoneRecords().isEmpty()); assertTrue(c.enforcing); assertTrue(ZoneHold.held); assertTrue(pauses.pausedNow())
        assertTrue("admission enforces from the held start", pauses.zonePausedAt(wall))
        assertTrue(admission().decide(meta("x", wall), ClockOffset(0), wall) is Admission.AckOnly)
        assertTrue("and the push to the glasses carries the flag", publishes > 0)
        failAdd = false; c.tick()
        assertFalse(ZoneHold.held); assertEquals(clear, pauses.zoneRecords().single().createdAtMs)
    }

    // P1-9: the zone store.
    @Test fun aMalformedZoneListHoldsCaptureInsteadOfEndingTheZonePauses() = runTest {
        c.upsert(zone); c.start(); c.at(100.0); assertEquals(1, pauses.zoneRecords().size)
        storePrefs.edit().putString("zones", "{garbage").commit()
        c.tick()
        assertTrue(c.enforcing); assertTrue("the existing zone pause is NOT ended", pauses.zoneRecords().any { it.pauseId.startsWith("zone:z1:") })
        assertTrue(pauses.zoneRecords().any { it.pauseId.startsWith("zone:_unreadable:") }); assertTrue(notes.events.last().startsWith("paused:") && notes.events.last().endsWith(":true"))
        assertEquals(ZoneChange.PROTECTED, c.upsert(zone.copy(bufferM = 1))); assertEquals(ZoneChange.PROTECTED, c.remove("z1")); assertEquals(ZoneChange.PROTECTED, c.setEnabled("z1", false))
        assertEquals(ZoneChange.OK, c.replaceAll(listOf(zone))); c.at2(50_000.0)
        assertFalse(c.enforcing); assertTrue(pauses.zoneRecords().isEmpty())
    }

    @Test fun aZoneChangeThatDidNotReachTheDiskIsReportedAndNotApplied() = runTest {
        val failing = FailingPrefs(ctx.getSharedPreferences("r1_fail_${System.nanoTime()}", 0))
        val cc = controller(failing); cc.upsert(zone); cc.start(); cc.at2(50_000.0)
        failing.fail = true
        assertEquals(ZoneChange.STORE_FAILED, cc.setEnabled("z1", false)); assertEquals(ZoneChange.STORE_FAILED, cc.remove("z1")); assertEquals(ZoneChange.STORE_FAILED, cc.upsert(zone.copy(bufferM = 9)))
        assertTrue(cc.zones().single().enabled); assertEquals(300, cc.zones().single().bufferM)
    }

    // P2-11: mutation before initialisation.
    @Test fun aControllerThatHasNotJudgedTheZonesYetRefusesToWeakenThem() = runTest {
        c.upsert(zone)
        val fresh = controller() // a controller obtained before the hub initialised it: the evaluator holds no zones
        assertEquals(ZoneChange.NOT_READY, fresh.setEnabled("z1", false)); assertEquals(ZoneChange.NOT_READY, fresh.remove("z1")); assertEquals(ZoneChange.NOT_READY, fresh.upsert(zone.copy(south = 10.005)))
        assertEquals(listOf(zone), fresh.zones())
        fresh.start(); fresh.at2(50_000.0); assertEquals(ZoneChange.OK, fresh.setEnabled("z1", false))
        // and again while recovering
        fresh.beginRestart(); assertEquals(ZoneChange.NOT_READY, fresh.remove("z1"))
    }

    // P1-6: clock rollback.
    @Test fun aBackwardClockMoveCannotLetAnInsideFrameMapBeforeTheIntervalStart() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0); c.at(100.0)
        val start = pauses.zoneRecords().single().createdAtMs
        val insideOld = wall + 500L // a frame taken inside, in the OLD phone time
        // the phone clock jumps back 10 minutes, then a fresh hello re-measures the offset: the frame now maps 10 minutes earlier than the start of the interval it belongs to
        wall -= 600_000L; pauses.zonePausedAt(wall) // the hub observes the clock
        val mapped = insideOld - 600_000L
        assertTrue(mapped < start)
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), admission().decide(meta("f", mapped), ClockOffset(0), wall))
        ClockGuard.reset(); assertTrue("without the guard it was admitted (the Codex probe)", admission().decide(meta("g", mapped), ClockOffset(0), wall) is Admission.Ingest)
    }

    @Test fun anIntervalNeverEndsBeforeItStarts() = runTest {
        c.upsert(zone); c.start(); c.at(100.0)
        val start = pauses.zoneRecords().single().createdAtMs
        wall = start - 3_600_000L // the clock moved back an hour before the exit
        pauses.resumeZones(pauses.zoneRecords().map { it.pauseId }.toSet())
        val iv = db.pauseDao().intervals().single { it.pauseId.startsWith("zone:") }
        assertTrue(iv.effectiveEndMs!! >= iv.startMs)
    }

    @Test fun theWidenedWindowIsRememberedAcrossAProcessDeath() {
        val saved = ArrayList<Triple<Long, Long, Long>>()
        ClockGuard.restore(0, 0, 0) { p, r, a -> saved += Triple(p, r, a) }
        ClockGuard.observe(1_000_000L); ClockGuard.observe(400_000L)
        assertEquals(600_000L, ClockGuard.widenMs()); assertEquals(600_000L, saved.last().second)
        ClockGuard.reset(); ClockGuard.restore(saved.last().first, saved.last().second, saved.last().third) { _, _, _ -> }
        assertEquals(600_000L, ClockGuard.widenMs())
        ClockGuard.observe(400_000L + 8 * 24 * 3_600_000L); assertEquals("expires with the retained history", 0L, ClockGuard.widenMs())
    }

    @Test fun theZoneMemoNeverHoldsACoordinate() = runTest {
        c.upsert(zone); c.start(); c.at2(50_000.0)
        val stored = statePrefs.all.values.joinToString()
        assertFalse(stored, Regex("""\d+\.\d{3,}""").containsMatchIn(stored.replace(Regex("""\d{10,}"""), "")))
        assertNull(null)
    }
}
