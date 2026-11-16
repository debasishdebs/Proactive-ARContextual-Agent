package com.rokid.context.phone.zone

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.zone.FixCadence
import com.rokid.context.core.model.zone.FixPriority
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.core.model.zone.ZoneFix
import com.rokid.context.core.model.zone.ZoneState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The cadence the shell asks for follows the zone distance; it asks only while a zone exists and access is valid, and reports invalid access so the zone fails closed. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TripLocationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val zone = PauseZone("z1", "Reserve", 10.0, 20.0, 10.01, 20.01, bufferM = 300)
    private val m = 111_194.93
    private var el = 1_000_000L

    private class Sink : ZoneSink {
        val records = ArrayList<PauseRecord>()
        override suspend fun activeZoneRecords() = records.toList()
        override suspend fun add(record: PauseRecord) { records += record }
        override suspend fun end(ids: Set<String>) { records.removeAll { it.pauseId in ids } }
        override suspend fun publish() {}
    }
    private class Plat(var valid: Boolean = true) : TripLocation.Platform {
        val requests = ArrayList<FixCadence?>()
        override fun accessValid() = valid
        override fun request(cadence: FixCadence?) { requests += cadence }
    }

    private val sink = Sink()
    private val store = ZoneStore(ctx.getSharedPreferences("tl_${System.nanoTime()}", 0))
    private val c = ZoneController(store, sink, object : ZoneNotifier { override fun paused(zoneNames: List<String>, locationUnknown: Boolean) {}; override fun cleared() {} }, elapsed = { el })

    private fun TestScope.trip(p: Plat) = TripLocation(c, p, backgroundScope, tickMs = 15_000L).also { it.start() }
    private fun fix(meters: Double) = ZoneFix(el, 10.0 - meters / m, 20.005, 10f)

    @Test fun nothingIsRequestedWhileNoZoneExists() = runTest {
        val p = Plat(); trip(p); c.start(); runCurrent(); advanceTimeBy(60_000)
        assertTrue("${p.requests}", p.requests.all { it == null })
    }

    @Test fun cadenceFollowsTheDistanceFromFarToInside() = runTest {
        val p = Plat(); val t = trip(p); c.upsert(zone); c.start(); runCurrent()
        assertEquals("no fix yet: high accuracy to find one", FixCadence(FixPriority.HIGH, 10_000L), p.requests.last())
        el += 1; c.onFix(fix(80_000.0)); el += 1; c.onFix(fix(80_000.0)); runCurrent(); assertEquals(FixCadence(FixPriority.BALANCED, 300_000L), p.requests.last())
        el += 1; c.onFix(fix(20_000.0)); runCurrent(); assertEquals(FixCadence(FixPriority.BALANCED, 30_000L), p.requests.last())
        el += 1; c.onFix(fix(1_500.0)); runCurrent(); assertEquals(FixCadence(FixPriority.HIGH, 10_000L), p.requests.last())
        el += 1; c.onFix(fix(50.0)); runCurrent(); assertEquals(FixCadence(FixPriority.HIGH, 10_000L), p.requests.last())
        assertEquals(ZoneState.INSIDE, c.snapshot.value.evaluation.state("z1")); t.hashCode()
    }

    @Test fun aPlatformFixReachesTheController() = runTest {
        val p = Plat(); val t = trip(p); c.upsert(zone); c.start(); runCurrent()
        el += 1; t.onFix(fix(80_000.0)); runCurrent(); el += 1; t.onFix(fix(80_000.0)); runCurrent()
        assertEquals(ZoneState.OUTSIDE, c.snapshot.value.evaluation.state("z1"))
    }

    @Test fun invalidAccessStopsTheRequestAndFailsTheZoneClosedAndComesBack() = runTest {
        val p = Plat(); trip(p); c.upsert(zone); c.start(); runCurrent()
        el += 1; c.onFix(fix(1_000.0)); el += 1; c.onFix(fix(1_000.0)); runCurrent()
        p.valid = false; advanceTimeBy(15_001); runCurrent()
        assertNull("no request without valid access", p.requests.last()); assertEquals(1, sink.records.size)
        p.valid = true; advanceTimeBy(15_000); runCurrent()
        assertEquals(FixCadence(FixPriority.HIGH, 10_000L), p.requests.last())
        el += 1; c.onFix(fix(1_000.0)); el += 1; c.onFix(fix(1_000.0)); runCurrent(); assertTrue(sink.records.isEmpty())
    }

    @Test fun removingTheLastZoneStopsTheRequest() = runTest {
        val p = Plat(); trip(p); c.upsert(zone); c.start(); runCurrent(); el += 1; c.onFix(fix(80_000.0)); el += 1; c.onFix(fix(80_000.0)); runCurrent()
        c.remove("z1"); runCurrent()
        assertNull(p.requests.last())
    }

    // Codex zones r1 P2-13: the registration Task's outcome decides; a failure is retried and reported, a success makes the cadence current.
    @Test fun aRegistrationThatFailsLaterIsNeverMarkedCurrentAndIsRetried() {
        val r = TripLocation.Registration(); val hi = FixCadence(FixPriority.HIGH, 10_000L)
        assertTrue(r.wants(hi)); r.begin(hi); assertFalse("in flight: not asked twice", r.wants(hi))
        r.done(hi, false)
        assertTrue("failed: the same request is made again", r.wants(hi)); assertTrue(r.failed); assertNull(r.current)
        r.begin(hi); r.done(hi, true)
        assertFalse(r.wants(hi)); assertFalse(r.failed); assertEquals(hi, r.current)
        assertTrue(r.wants(null)); r.clear(); assertFalse(r.wants(null))
    }

    @Test fun aFailedRegistrationFailsTheZoneClosedUntilItSucceeds() = runTest {
        val p = object : TripLocation.Platform {
            var failed = false; val requests = ArrayList<FixCadence?>()
            override fun accessValid() = true
            override fun request(cadence: FixCadence?) { requests += cadence }
            override val registrationFailed: Boolean get() = failed
        }
        trip2(p); c.upsert(zone); c.start(); runCurrent()
        el += 1; c.onFix(fix(1_000.0)); el += 1; c.onFix(fix(1_000.0)); runCurrent(); assertTrue(sink.records.isEmpty()) // APPROACH, fixes flowing
        p.failed = true; advanceTimeBy(15_001); runCurrent()
        assertEquals("no fixes are coming: the zone fails closed", 1, sink.records.size)
        p.failed = false; advanceTimeBy(15_001); runCurrent()
        assertEquals("a successful retry restores nothing by itself", 1, sink.records.size)
        el += 1; c.onFix(fix(1_000.0)); el += 1; c.onFix(fix(1_000.0)); runCurrent(); assertTrue(sink.records.isEmpty())
    }

    // P3-14: no zones, no supervision; zones: one reconcile per tick.
    @Test fun withNoZoneTheSupervisionLoopIsSuspendedAndCostsNothing() = runTest {
        var checks = 0
        val p = object : TripLocation.Platform { override fun accessValid(): Boolean { checks++; return true }; override fun request(cadence: FixCadence?) {} }
        trip2(p); c.start(); runCurrent(); advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(0, checks)
    }

    @Test fun withZonesEachTickReconcilesOnce() = runTest {
        var ticks = 0
        val counting = object : ZoneSink {
            override suspend fun activeZoneRecords(): List<PauseRecord> { ticks++; return emptyList() }
            override suspend fun add(record: PauseRecord) {}
            override suspend fun end(ids: Set<String>) {}
            override suspend fun publish() {}
        }
        val cc = ZoneController(ZoneStore(ctx.getSharedPreferences("tl2_${System.nanoTime()}", 0)), counting, object : ZoneNotifier { override fun paused(zoneNames: List<String>, locationUnknown: Boolean) {}; override fun cleared() {} }, elapsed = { el })
        val p = Plat(); TripLocation(cc, p, backgroundScope, tickMs = 15_000L).start()
        cc.upsert(zone); cc.start(); runCurrent(); val before = ticks
        advanceTimeBy(15_001); runCurrent()
        assertEquals("one reconcile per supervision tick", 1, ticks - before)
    }

    private fun TestScope.trip2(p: TripLocation.Platform) = TripLocation(c, p, backgroundScope, tickMs = 15_000L).also { it.start() }
}
