package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.zone.ZonePause
import com.rokid.context.link.Admission
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.MarkLookup
import com.rokid.context.link.MarkPhotoAllowance
import com.rokid.context.link.MarkState
import com.rokid.context.link.ReceivedFrameLedger
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Pause zones on the phone's Room history: `zone:<id>:<n>` PHONE records; no Resume names one; only the zone logic ends them; the zone interval gates frames. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubPausesZoneTest {
    private val db = testDb(ApplicationProvider.getApplicationContext())
    private var t = 1_793_000_000_000L
    private val pauses = HubPauses(db.pauseDao()) { t }
    @After fun close() = db.close()

    private fun zoneRecord(id: String = "jarawa", n: Long = 1) = PauseRecord(ZonePause.pauseId(id, n), PauseOrigin.PHONE, t, null)

    @Test fun aZoneRecordIsAnActivePhonePauseWithATheZoneId() = runTest {
        pauses.add(zoneRecord())
        assertEquals(listOf("zone:jarawa:1"), pauses.active().map { it.pauseId })
        assertEquals(PauseOrigin.PHONE, pauses.active().single().origin)
        assertTrue(pauses.pausedNow()); assertEquals(listOf("zone:jarawa:1"), pauses.zoneRecords().map { it.pauseId })
    }

    @Test fun noOrdinaryResumeEndsAZoneRecord() = runTest {
        pauses.add(zoneRecord()); pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null))
        pauses.resume(setOf("zone:jarawa:1", "p1"))
        assertEquals(listOf("zone:jarawa:1"), pauses.active().map { it.pauseId })
        pauses.resume(setOf("zone:jarawa:1"), atMs = t - 10_000) // a Resume made offline, too
        assertEquals(1, pauses.active().size)
        assertEquals(emptyList<PauseRecord>(), pauses.endQuietHours().let { pauses.stored().filter { it.origin == PauseOrigin.QUIET_HOURS } })
    }

    @Test fun onlyResumeZonesEndsItByNameAndIgnoresOtherIds() = runTest {
        pauses.add(zoneRecord()); pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null))
        t += 5_000
        pauses.resumeZones(setOf("zone:jarawa:1", "p1"))
        assertEquals(listOf("p1"), pauses.active().map { it.pauseId })
        assertEquals(listOf("zone:jarawa:1"), pauses.recentlyResumedIds())
    }

    @Test fun theZoneIntervalCoversFramesFromItsStartUntilItsEndHalfOpen() = runTest {
        val start = t
        pauses.add(zoneRecord())
        assertTrue(pauses.zonePausedAt(start)); assertTrue(pauses.zonePausedAt(start + 60_000)); assertFalse(pauses.zonePausedAt(start - 1))
        t += 120_000
        pauses.resumeZones(setOf("zone:jarawa:1"))
        assertTrue("a frame taken inside the closed interval is still a zone frame", pauses.zonePausedAt(start + 119_999)); assertFalse(pauses.zonePausedAt(start + 120_000))
        assertTrue(pauses.pausedAt(start + 60_000))
    }

    @Test fun anOrdinaryPauseIsNotAZoneInterval() = runTest {
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null)); pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, t, null))
        assertTrue(pauses.pausedAt(t)); assertFalse(pauses.zonePausedAt(t))
        assertEquals(emptyList<PauseRecord>(), pauses.zoneRecords())
    }

    @Test fun aClosedZoneRecordIsNeverResurrectedByALateEcho() = runTest {
        pauses.add(zoneRecord()); t += 1_000; pauses.resumeZones(setOf("zone:jarawa:1"))
        pauses.union(listOf(zoneRecord())) // the glasses echo it in a late hello
        assertTrue(pauses.active().isEmpty())
    }

    @Test fun eachEntryGetsItsOwnNumberedRecordAndTheIntervalsStack() = runTest {
        val a = t; pauses.add(zoneRecord(n = 1)); t += 10_000; pauses.resumeZones(setOf("zone:jarawa:1"))
        t += 10_000; val b = t; pauses.add(zoneRecord(n = 2))
        assertEquals(listOf("zone:jarawa:2"), pauses.active().map { it.pauseId })
        assertTrue(pauses.zonePausedAt(a + 5_000)); assertFalse(pauses.zonePausedAt(a + 15_000)); assertTrue(pauses.zonePausedAt(b + 1))
    }

    /** The real gate: the hub's Room history with a mark whose allowance would pass an ordinary pause. A zone interval beats it, and the allowance is never consumed. */
    @Test fun frameAdmissionOverTheRealHistoryDropsMarkAndOrdinaryFramesInsideAZone() = runTest {
        class Marks : MarkLookup { var asked = 0; override suspend fun state(markRef: String): MarkState { asked++; return MarkState.OPEN }; override suspend fun claim(markRef: String, frameId: String) = true }
        val marks = Marks()
        val dir = java.nio.file.Files.createTempDirectory("zadm").toFile()
        val admission = FrameAdmission(ReceivedFrameLedger(File(dir, "ledger.txt")), pauses, marks = MarkPhotoAllowance(marks))
        fun meta(id: String, ref: String?, at: Long) = FrameMeta(frameId = id, chunkIdx = 0, chunkCount = 1, crc32 = 1, capturedAtMs = at, markRef = ref)
        val start = t
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, start - 600_000, null)) // an ordinary open pause (the mark allowance would pass it)
        assertTrue(admission.decide(meta("a", "ep:1", start - 1_000), ClockOffset(0), t + 10_000) is Admission.Ingest)
        pauses.add(zoneRecord())
        t += 60_000
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), admission.decide(meta("b", "ep:2", start + 30_000), ClockOffset(0), t))
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), admission.decide(meta("c", null, start + 31_000), ClockOffset(0), t))
        assertEquals("only the frame before the zone asked the allowance", 1, marks.asked)
        pauses.resumeZones(setOf("zone:jarawa:1")); t += 1_000
        assertTrue("after the zone, the ordinary pause and the allowance decide again", admission.decide(meta("d", "ep:3", t - 500), ClockOffset(0), t) is Admission.Ingest)
        assertEquals(Admission.AckOnly(FrameAckReason.PAUSED_DROP), admission.decide(meta("e", "ep:2", start + 30_500), ClockOffset(0), t))
    }
}
