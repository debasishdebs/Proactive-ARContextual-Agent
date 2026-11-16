package com.rokid.context.phone.zone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.zone.FixCadence
import com.rokid.context.core.model.zone.FixPriority
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneEvaluator
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.core.model.zone.ZonePause
import com.rokid.context.core.model.zone.ZonePresets
import com.rokid.context.core.model.zone.ZoneState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ZoneControllerTest {
    @org.junit.Before fun resetGlobals() { ClockGuard.reset(); ZoneHold.set(null) }
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val zone = PauseZone("z1", "Reserve", south = 10.0, west = 20.0, north = 10.01, east = 20.01, bufferM = 300)
    private val m = 111_194.93
    private var el = 1_000_000L
    private var wall = 1_793_000_000_000L

    private class Sink : ZoneSink {
        val records = ArrayList<PauseRecord>(); var publishes = 0; var failAdd = false
        override suspend fun activeZoneRecords() = records.toList()
        override suspend fun add(record: PauseRecord) { if (failAdd) error("db"); records += record }
        override suspend fun end(ids: Set<String>) { records.removeAll { it.pauseId in ids } }
        override suspend fun publish() { publishes++ }
    }
    private class Notes : ZoneNotifier {
        val events = ArrayList<String>()
        override fun paused(zoneNames: List<String>, locationUnknown: Boolean) { events += "paused:${zoneNames.joinToString()}:${if (locationUnknown) "unknown" else "known"}" }
        override fun cleared() { events += "cleared" }
    }

    private val sink = Sink(); private val notes = Notes(); private val logs = ArrayList<String>(); private var zonesChanged = 0
    private val store = ZoneStore(ctx.getSharedPreferences("zt_${System.nanoTime()}", 0))
    private val c = ZoneController(store, sink, notes, elapsed = { el }, wall = { wall }, log = { logs += it }, onZonesChanged = { zonesChanged++ })

    private fun fix(meters: Double, acc: Float = 10f) = ZoneFix(el, 10.0 - meters / m, 20.005, acc)
    private suspend fun at(meters: Double, acc: Float = 10f, advance: Long = 1_000L) { el += advance; wall += advance; c.onFix(fix(meters, acc)) }
    /** Two qualifying fixes: the exit of a fail-closed or inside reading. */
    private suspend fun at2(meters: Double, acc: Float = 10f) { at(meters, acc); at(meters, acc) }

    @Test fun noZonesMeansNoRecordsNoFixesWantedAndApproachFalse() = runTest {
        c.start()
        assertTrue(sink.records.isEmpty()); assertNull(c.cadence.value); assertFalse(c.wantsLocation()); assertFalse(c.approach)
    }

    @Test fun beforeTheFirstFixAnEnabledZoneIsInsideAndPausesWithTheZoneNameOnly() = runTest {
        c.upsert(zone); c.start()
        assertEquals(1, sink.records.size); assertTrue(ZonePause.isZoneRecord(sink.records.single().pauseId)); assertTrue(sink.records.single().pauseId.startsWith("zone:z1:"))
        assertEquals(listOf("paused:Reserve:unknown"), notes.events); assertTrue(c.approach)
        assertEquals(FixCadence(FixPriority.HIGH, 10_000L), c.cadence.value)
    }

    @Test fun aFarFixEndsTheFailClosedRecordAndAnEntryAddsANewNumberedOne() = runTest {
        c.upsert(zone); c.start(); val first = sink.records.single().pauseId
        at(50_000.0); assertEquals("one fix does not end the fail-closed start", 1, sink.records.size); at(50_000.0)
        assertTrue(sink.records.isEmpty()); assertEquals("cleared", notes.events.last()); assertFalse(c.approach)
        assertEquals(FixCadence(FixPriority.BALANCED, 300_000L), c.cadence.value)
        at(100.0)
        val second = sink.records.single().pauseId
        assertTrue(second != first && second.startsWith("zone:z1:")); assertEquals("paused:Reserve:known", notes.events.last())
    }

    @Test fun exitNeedsTwoGoodFixesAndThenResumesByName() = runTest {
        c.upsert(zone); c.start(); at(100.0)
        at(600.0); assertEquals(1, sink.records.size)
        at(600.0); assertTrue(sink.records.isEmpty()); assertTrue("still in the approach band", c.approach)
        assertEquals(ZoneState.APPROACH, c.snapshot.value.evaluation.state("z1"))
    }

    @Test fun theApproachFlagIsPublishedWhenItChangesAndOnEveryRecordChange() = runTest {
        c.upsert(zone); c.start(); at2(50_000.0)
        val before = sink.publishes
        at(51_000.0); assertEquals("nothing changed: no push", before, sink.publishes)
        at(1_500.0); assertTrue("approach began", sink.publishes > before)
    }

    @Test fun aStaleFixFailsClosedBackdatedToTheLastTrustedFix() = runTest {
        c.upsert(zone); c.start(); at2(1_000.0); val lastFixWall = wall
        el += 200_000; wall += 200_000; c.tick() // 3 min 20 s without a fix while APPROACH
        val r = sink.records.single()
        assertEquals("frames since the last trusted fix are inside the zone interval", lastFixWall, r.createdAtMs)
        assertEquals("paused:Reserve:unknown", notes.events.last())
        at2(1_000.0); assertTrue("two fresh fixes read APPROACH again: the record ends", sink.records.isEmpty())
    }

    @Test fun locationRevokedWhileApproachFailsClosedAndALongOutsideSpellDoesNot() = runTest {
        c.upsert(zone); c.start(); at2(1_000.0); c.onLocationAvailable(false)
        assertEquals(1, sink.records.size); c.onLocationAvailable(true); assertEquals("restoring access clears nothing", 1, sink.records.size)
        at2(1_000.0); assertTrue(sink.records.isEmpty())
        at2(80_000.0); c.onLocationAvailable(false); assertTrue("outside: not paused by itself", sink.records.isEmpty())
    }

    @Test fun aFailedRecordWriteIsRetriedByTheNextTick() = runTest {
        sink.failAdd = true; c.upsert(zone); c.start()
        assertTrue(sink.records.isEmpty()); assertTrue(logs.any { it.startsWith("zone pause not stored") })
        assertTrue("held in memory while the record cannot be stored", ZoneHold.held && c.enforcing && c.approach)
        sink.failAdd = false; c.tick(); assertEquals(1, sink.records.size); assertFalse(ZoneHold.held)
    }

    @Test fun aZoneCannotBeDisabledEditedOrDeletedWhileInsideNorBeforeItsFirstFix() = runTest {
        c.upsert(zone); c.start()
        assertEquals(ZoneChange.BLOCKED_INSIDE, c.setEnabled("z1", false)); assertEquals(ZoneChange.BLOCKED_INSIDE, c.remove("z1"))
        assertEquals(ZoneChange.BLOCKED_INSIDE, c.upsert(zone.copy(bufferM = 1)))
        at(100.0)
        assertEquals(ZoneChange.BLOCKED_INSIDE, c.setEnabled("z1", false)); assertEquals(ZoneChange.BLOCKED_INSIDE, c.remove("z1")); assertEquals(1, c.zones().size)
        assertTrue(c.zones().single().enabled); assertEquals(300, c.zones().single().bufferM); assertEquals(1, sink.records.size)
    }

    @Test fun outsideAZoneCanBeDisabledEditedAndDeletedAndItsRecordsEnd() = runTest {
        c.upsert(zone); c.start(); at2(50_000.0)
        assertEquals(ZoneChange.OK, c.upsert(zone.copy(bufferM = 500))); assertEquals(500, c.zones().single().bufferM)
        assertEquals(ZoneChange.OK, c.setEnabled("z1", false)); assertFalse(c.wantsLocation()); assertNull(c.cadence.value)
        assertEquals(ZoneChange.OK, c.setEnabled("z1", true)); assertTrue(c.wantsLocation())
        assertEquals(ZoneChange.OK, c.remove("z1")); assertTrue(c.zones().isEmpty()); assertTrue(sink.records.isEmpty())
        assertTrue("the service is told so it can drop the location type", zonesChanged >= 3)
    }

    @Test fun anInvalidOrUnknownZoneIsRefused() = runTest {
        assertEquals(ZoneChange.INVALID, c.upsert(zone.copy(south = 11.0))); assertEquals(ZoneChange.NOT_FOUND, c.setEnabled("nope", false)); assertEquals(ZoneChange.NOT_FOUND, c.remove("nope"))
    }

    @Test fun aZoneAddedWhileInsideAnotherIsJudgedByTheFreshLastFixAtOnce() = runTest {
        c.upsert(zone); c.start(); at2(30_000.0)
        val near = PauseZone("z2", "Near", south = 10.0, west = 20.0, north = 10.01, east = 20.01, bufferM = 40_000)
        c.upsert(near)
        assertEquals(listOf("zone:z2:"), sink.records.map { it.pauseId.substringBeforeLast(':') + ":" })
        assertEquals(ZoneState.INSIDE, c.snapshot.value.evaluation.state("z2")); assertEquals(ZoneState.OUTSIDE, c.snapshot.value.evaluation.state("z1"))
    }

    @Test fun addingThePresetIsIdempotentAndThePresetBlocksTheSameWay() = runTest {
        c.start(); assertEquals(ZoneChange.OK, c.addPreset(ZonePresets.JARAWA)); assertEquals(ZoneChange.OK, c.addPreset(ZonePresets.JARAWA))
        assertEquals(1, c.zones().size); assertEquals(5_000, c.zones().single().bufferM)
        assertEquals(ZoneChange.BLOCKED_INSIDE, c.remove("jarawa")) // no fix yet: fail closed
        el += 1; c.onFix(ZoneFix(el, 11.97, 92.72, 10f))
        assertEquals(ZoneChange.BLOCKED_INSIDE, c.setEnabled("jarawa", false)); assertEquals(1, sink.records.size)
    }

    @Test fun theLogsCarryCountsAndNamesOfStatesNeverCoordinatesOrNames() = runTest {
        c.upsert(zone); c.start(); at(100.0); at(900.0); at(900.0)
        assertTrue(logs.isNotEmpty())
        logs.forEach { l -> assertFalse(l, l.contains("Reserve")); assertFalse(l, Regex("""\d+\.\d{3,}""").containsMatchIn(l)); assertFalse(l, l.contains("10.0")) }
    }

    @Test fun zonesSurviveARestartOfTheStoreButTheirStateStartsClosed() = runTest {
        c.upsert(zone)
        val c2 = ZoneController(store, sink, notes, elapsed = { el }, wall = { wall })
        c2.start()
        assertEquals(listOf("z1"), c2.zones().map { it.id }); assertEquals(ZoneState.INSIDE, c2.snapshot.value.evaluation.state("z1")); assertTrue(c2.approach)
    }
}
