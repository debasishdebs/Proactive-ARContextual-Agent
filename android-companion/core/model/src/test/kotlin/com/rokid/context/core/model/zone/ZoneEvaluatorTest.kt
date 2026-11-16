package com.rokid.context.core.model.zone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneEvaluatorTest {
    private val zone = PauseZone("z1", "Test", south = 10.0, west = 20.0, north = 10.01, east = 20.01, bufferM = 300)
    private val m = 111_194.93 // metres per degree of latitude (the evaluator's sphere)
    private var t = 1_000_000L

    /** A point due south of the box edge, [meters] from it, in the box's longitude range (so the distance is exactly that). */
    private fun south(meters: Double, acc: Float = 10f) = ZoneFix(t, 10.0 - meters / m, 20.005, acc)

    /** No fix yet: every zone reads INSIDE (NO_FIX, latched). */
    private fun fresh(vararg zones: PauseZone = arrayOf(zone)) = ZoneEvaluator().also { it.setZones(zones.toList(), t) }
    /** Established outside: two qualifying far fixes after the start (the exit of the fail-closed start). */
    private fun evaluator(vararg zones: PauseZone = arrayOf(zone)) = fresh(*zones).also { e -> repeat(2) { t += 1_000; e.onFix(south(50_000.0), t) } }
    private fun ZoneEvaluator.fix(meters: Double, acc: Float = 10f, advance: Long = 1_000L): ZoneState {
        t += advance
        onFix(south(meters, acc), t)
        return evaluate(t).state("z1")!!
    }

    @Test fun noFixYetReadsInsideForEveryEnabledZone() {
        val e = fresh(); val s = e.evaluate(t).zones.single()
        assertEquals(ZoneState.INSIDE, s.state); assertEquals(ZoneState.INSIDE, s.measured); assertEquals(ZoneReason.NO_FIX, s.reason)
    }

    @Test fun noZonesIsOutsideAndEmpty() {
        val e = ZoneEvaluator(); assertEquals(ZoneState.OUTSIDE, e.evaluate(t).overall); assertTrue(e.evaluate(t).zones.isEmpty())
        e.onFix(south(0.0), t); assertEquals(ZoneState.OUTSIDE, e.evaluate(t).overall)
    }

    @Test fun farFixIsOutside() { assertEquals(ZoneState.OUTSIDE, evaluator().fix(50_000.0)) }

    @Test fun entersAtTheBufferEdge() {
        assertEquals(ZoneState.INSIDE, evaluator().fix(299.0, acc = 0f))
        assertEquals(ZoneState.APPROACH, evaluator().fix(301.0, acc = 0f))
        assertEquals(ZoneState.INSIDE, evaluator().fix(305.0, acc = 10f)) // the accuracy counts against the user
        assertEquals(ZoneState.INSIDE, evaluator().fix(0.0))
    }

    @Test fun insideTheBoxIsInsideEvenWithAHugeBuffer() {
        val e = evaluator(); t += 1; e.onFix(ZoneFix(t, 10.005, 20.005, 5f), t); assertEquals(ZoneState.INSIDE, e.evaluate(t).overall)
    }

    @Test fun aVagueFixNearTheZoneEnters() {
        assertEquals(ZoneState.INSIDE, evaluator().fix(500.0, acc = 300f)) // 500 - 300 = 200 <= 300
        assertEquals(ZoneState.APPROACH, evaluator().fix(500.0, acc = 50f)) // 450 > 300
    }

    @Test fun theAccuracyCreditIsCapped() {
        assertEquals(ZoneState.APPROACH, evaluator().fix(1_500.0, acc = 5_000f)) // credit 1000 -> 500 > 300
        assertEquals(ZoneState.INSIDE, evaluator().fix(1_200.0, acc = 5_000f))   // 200 <= 300
    }

    @Test fun anUnknownAccuracyIsTheWorstCase() {
        assertEquals(ZoneState.INSIDE, evaluator().fix(1_000.0, acc = Float.NaN)) // credit 1000 -> 0
        assertEquals(ZoneState.INSIDE, evaluator().fix(1_000.0, acc = -1f))
    }

    @Test fun leavesOnlyAfterTwoConsecutiveGoodFixesBeyondTheHysteresis() {
        val e = evaluator(); e.fix(100.0)
        assertEquals(ZoneState.INSIDE, e.fix(450.0))  // between buffer and buffer + 200: stays
        assertEquals(ZoneState.INSIDE, e.fix(501.0))  // first fix beyond 500
        assertEquals(ZoneState.APPROACH, e.fix(501.0)) // second: leaves, still within the approach band
    }

    @Test fun justInsideTheHysteresisEdgeDoesNotCount() {
        val e = evaluator(); e.fix(100.0)
        assertEquals(ZoneState.INSIDE, e.fix(499.0)); assertEquals(ZoneState.INSIDE, e.fix(499.0)); assertEquals(ZoneState.INSIDE, e.fix(499.0))
    }

    @Test fun anInsideFixBetweenRestartsTheCount() {
        val e = evaluator(); e.fix(100.0)
        assertEquals(ZoneState.INSIDE, e.fix(600.0))
        assertEquals(ZoneState.INSIDE, e.fix(350.0)) // in the hysteresis band: the count restarts
        assertEquals(ZoneState.INSIDE, e.fix(600.0))
        assertEquals(ZoneState.APPROACH, e.fix(600.0))
    }

    @Test fun aFixEnteringAgainRestartsTheCount() {
        val e = evaluator(); e.fix(100.0)
        e.fix(600.0); assertEquals(ZoneState.INSIDE, e.fix(10.0)); assertEquals(ZoneState.INSIDE, e.fix(600.0))
    }

    @Test fun aPoorAccuracyFixNeverCountsTowardLeaving() {
        val e = evaluator(); e.fix(100.0)
        assertEquals(ZoneState.INSIDE, e.fix(900.0, acc = 101f))
        assertEquals(ZoneState.INSIDE, e.fix(900.0, acc = 101f))
        assertEquals(ZoneState.INSIDE, e.fix(900.0, acc = 100.5f))
        assertEquals(ZoneState.INSIDE, e.fix(900.0, acc = 100f)) // one good fix
        assertEquals(ZoneState.APPROACH, e.fix(900.0, acc = 100f)) // two in a row: accuracy 100 is allowed
    }

    @Test fun aPoorFixBetweenTwoGoodOnesRestartsTheCount() {
        val e = evaluator(); e.fix(100.0)
        e.fix(900.0); assertEquals(ZoneState.INSIDE, e.fix(900.0, acc = 500f)); assertEquals(ZoneState.INSIDE, e.fix(900.0))
        assertEquals(ZoneState.APPROACH, e.fix(900.0))
    }

    @Test fun leavingFarGoesStraightToOutside() {
        val e = evaluator(); e.fix(100.0)
        e.fix(20_000.0); assertEquals(ZoneState.OUTSIDE, e.fix(20_000.0))
    }

    @Test fun approachHasItsOwnHysteresis() {
        val e = evaluator(); assertEquals(ZoneState.APPROACH, e.fix(1_000.0)) // within 300 + 2000
        assertEquals(ZoneState.APPROACH, e.fix(2_450.0)) // beyond 2300 but not 200 beyond it
        assertEquals(ZoneState.APPROACH, e.fix(2_600.0))
        assertEquals(ZoneState.APPROACH, e.fix(2_450.0)) // restarts
        assertEquals(ZoneState.APPROACH, e.fix(2_600.0))
        assertEquals(ZoneState.OUTSIDE, e.fix(2_600.0))
    }

    @Test fun anOutsideZoneApproachesAtOnce() {
        val e = evaluator(); e.fix(50_000.0); assertEquals(ZoneState.APPROACH, e.fix(2_000.0))
    }

    @Test fun aStaleFixWhileApproachOrInsideFailsClosed() {
        val e = evaluator(); e.fix(1_000.0)
        assertEquals(ZoneState.APPROACH, e.evaluate(t + ZoneEvaluator.STALE_MS).state("z1")) // exactly 3 min is still fresh
        val s = e.evaluate(t + ZoneEvaluator.STALE_MS + 1).zones.single()
        assertEquals(ZoneState.INSIDE, s.state); assertEquals(ZoneState.INSIDE, s.measured); assertEquals(ZoneReason.STALE_FIX, s.reason) // latched
        val i = evaluator(); i.fix(10.0)
        assertEquals(ZoneReason.STALE_FIX, i.evaluate(t + ZoneEvaluator.STALE_MS + 1).zones.single().reason)
    }

    @Test fun anOutsideZoneToleratesTheFarCadenceButNotAnHourOfSilence() {
        val e = evaluator(); e.fix(50_000.0)
        assertEquals(ZoneState.OUTSIDE, e.evaluate(t + ZoneEvaluator.OUTSIDE_STALE_MS).state("z1")) // the far cadence is 5 min
        val s = e.evaluate(t + ZoneEvaluator.OUTSIDE_STALE_MS + 1).zones.single() // nothing proves we are still outside: fail closed, latched
        assertEquals(ZoneState.INSIDE, s.state); assertEquals(ZoneReason.STALE_FIX, s.reason)
    }

    @Test fun anotherFixClearsTheStaleReading() {
        val e = evaluator(); e.fix(1_000.0)
        t += 10 * 60_000L
        assertEquals(ZoneState.INSIDE, e.evaluate(t).state("z1"))
        assertEquals(ZoneState.INSIDE, e.fix(1_000.0)) // latched: one fix does not clear it
        assertEquals(ZoneState.APPROACH, e.fix(1_000.0))
    }

    @Test fun locationOffWhileApproachOrInsideFailsClosed() {
        val e = evaluator(); e.fix(1_000.0); e.onLocationAvailable(false)
        assertEquals(ZoneReason.LOCATION_OFF, e.evaluate(t).zones.single().reason); assertEquals(ZoneState.INSIDE, e.evaluate(t).overall)
        e.onLocationAvailable(true); assertEquals(ZoneState.INSIDE, e.evaluate(t).overall) // restoring access clears nothing
        assertEquals(ZoneState.INSIDE, e.fix(1_000.0)); assertEquals(ZoneState.APPROACH, e.fix(1_000.0)) // two qualifying fixes do
    }

    @Test fun aVagueFarFixAfterRecoveryNeverClearsAFailClosedZone() { // Codex r1 P1-1: one 5 km-accuracy outside fix cleared the pause
        val e = evaluator(); e.fix(1_000.0); t += 10 * 60_000L; e.evaluate(t) // stale: latched INSIDE
        assertEquals(ZoneState.INSIDE, e.fix(50_000.0, acc = 5_000f)); assertEquals(ZoneState.INSIDE, e.fix(50_000.0, acc = 5_000f))
        assertEquals(ZoneState.INSIDE, e.fix(50_000.0)); assertEquals(ZoneState.OUTSIDE, e.fix(50_000.0))
    }

    @Test fun aRestartForgetsEverythingAndNeedsTwoQualifyingFixes() { // P1-5: a surviving process
        val e = evaluator(); e.fix(50_000.0); e.restart()
        assertEquals(ZoneReason.NO_FIX, e.evaluate(t).zones.single().reason); assertNull(e.evaluate(t).zones.single().lastClearMs)
        assertEquals(ZoneState.INSIDE, e.fix(50_000.0)); assertEquals(ZoneState.OUTSIDE, e.fix(50_000.0))
    }

    @Test fun aReplayedFixCountsOnce() { // P2-10
        val e = evaluator(); e.fix(10.0)
        val f1 = south(50_000.0); t += 1_000; val f2 = ZoneFix(t, f1.lat, f1.lon, 10f)
        assertTrue(e.onFix(f2, t)); assertFalse(e.onFix(f2, t)); assertFalse(e.onFix(f2.copy(), t))
        assertEquals(ZoneState.INSIDE, e.evaluate(t).state("z1")) // the same fix twice is not the second qualifying fix
        t += 1_000; assertTrue(e.onFix(south(50_000.0), t)); assertEquals(ZoneState.OUTSIDE, e.evaluate(t).state("z1"))
    }

    @Test fun theLastClearTimeIsTheLastFixThatProvedNotInside() {
        val e = evaluator(); e.fix(50_000.0); val clear = t; e.fix(10.0)
        assertEquals(clear, e.evaluate(t).zones.single().lastClearMs)
    }

    @Test fun locationOffWhileOutsideIsNotInsideByItself() {
        val e = evaluator(); e.fix(50_000.0); e.onLocationAvailable(false)
        assertEquals(ZoneState.OUTSIDE, e.evaluate(t).overall)
    }

    @Test fun aFixAlreadyStaleOnArrivalIsIgnored() {
        val e = fresh(); val old = ZoneFix(t - ZoneEvaluator.STALE_MS - 1, 10.0, 20.005, 5f)
        assertFalse(e.onFix(old, t)); assertEquals(ZoneReason.NO_FIX, e.evaluate(t).zones.single().reason)
    }

    @Test fun anOlderFixThanTheNewestIsIgnored() {
        val e = evaluator(); e.fix(10.0)
        val older = ZoneFix(t - 500, 10.0 - 50_000 / m, 20.005, 5f)
        assertFalse(e.onFix(older, t)); assertEquals(ZoneState.INSIDE, e.evaluate(t).overall)
    }

    @Test fun nonFiniteOrOutOfRangeFixesAreIgnored() {
        val e = fresh()
        assertFalse(e.onFix(ZoneFix(t, Double.NaN, 20.0, 5f), t)); assertFalse(e.onFix(ZoneFix(t, 10.0, Double.POSITIVE_INFINITY, 5f), t))
        assertFalse(e.onFix(ZoneFix(t, 95.0, 20.0, 5f), t)); assertFalse(e.onFix(ZoneFix(t, 10.0, 200.0, 5f), t))
        assertEquals(ZoneReason.NO_FIX, e.evaluate(t).zones.single().reason)
    }

    @Test fun aZoneAddedLaterIsJudgedByAFreshLastFixAndOtherwiseFailsClosed() {
        val e = ZoneEvaluator(); e.onFix(south(100.0), t)
        e.setZones(listOf(zone), t + 1_000L)
        assertEquals(ZoneState.INSIDE, e.evaluate(t + 1_000L).state("z1")); assertEquals(ZoneReason.NONE, e.evaluate(t + 1_000L).zones.single().reason)
        val f = ZoneEvaluator(); f.onFix(south(50_000.0), t)
        f.setZones(listOf(zone), t + ZoneEvaluator.STALE_MS + 1) // the last fix is too old to judge it
        assertEquals(ZoneReason.NO_FIX, f.evaluate(t + ZoneEvaluator.STALE_MS + 1).zones.single().reason)
    }

    @Test fun resettingTheSameZoneKeepsItsStateButAChangedBoxDoesNot() {
        val e = evaluator(); e.fix(1_000.0)
        e.setZones(listOf(zone), t); assertEquals(ZoneState.APPROACH, e.evaluate(t).state("z1"))
        e.setZones(listOf(zone.copy(bufferM = 5_000)), t) // judged again by the fresh last fix: 1000 <= 5000
        assertEquals(ZoneState.INSIDE, e.evaluate(t).state("z1"))
    }

    @Test fun disabledAndInvalidZonesAreNotTracked() {
        val e = evaluator(zone.copy(enabled = false), zone.copy(id = "bad:id"), zone.copy(id = "b", south = 11.0))
        assertEquals(0, e.zoneCount); assertEquals(ZoneState.OUTSIDE, e.evaluate(t).overall)
    }

    @Test fun theOverallStateIsTheWorstZone() {
        val far = PauseZone("z2", "Far", 40.0, 40.0, 40.01, 40.01, 300)
        val e = evaluator(zone, far); e.fix(100.0)
        assertEquals(ZoneState.INSIDE, e.evaluate(t).overall); assertEquals(ZoneState.OUTSIDE, e.evaluate(t).state("z2"))
        assertEquals(listOf("z1"), e.evaluate(t).inside.map { it.zoneId })
    }

    @Test fun removingAZoneDropsItsState() {
        val e = evaluator(); e.fix(100.0); e.setZones(emptyList(), t)
        assertTrue(e.evaluate(t).zones.isEmpty())
    }

    // TripCadence
    @Test fun cadenceFollowsTheDistance() {
        fun cad(meters: Double) = evaluator().also { it.fix(meters) }.let { TripCadence.select(it.evaluate(t)) }
        assertEquals(FixCadence(FixPriority.BALANCED, 5 * 60_000L), cad(25_001.0))
        assertEquals(FixCadence(FixPriority.BALANCED, 30_000L), cad(25_000.0 - 1))
        assertEquals(FixCadence(FixPriority.BALANCED, 30_000L), cad(2_700.0)) // beyond buffer + 2 km (2300): OUTSIDE
        assertEquals(FixCadence(FixPriority.HIGH, 10_000L), cad(2_000.0))
        assertEquals(FixCadence(FixPriority.HIGH, 10_000L), cad(0.0))
    }

    @Test fun cadenceIsHighWithoutAFixAndNullWithoutZones() {
        assertEquals(TripCadence.HIGH, TripCadence.select(fresh().evaluate(t)))
        assertNull(TripCadence.select(ZoneEvaluator().evaluate(t)))
    }

    @Test fun cadenceUsesTheNearestZoneAndStaysHighInTheHysteresisBand() {
        val far = PauseZone("z2", "Far", 40.0, 40.0, 40.01, 40.01, 300)
        val e = evaluator(zone, far); e.fix(10_000.0)
        assertEquals(30_000L, TripCadence.select(e.evaluate(t))!!.intervalMs)
        val g = evaluator(); g.fix(1_000.0); g.fix(2_450.0) // APPROACH measured, beyond the ring: still HIGH
        assertEquals(TripCadence.HIGH, TripCadence.select(g.evaluate(t)))
    }

    @Test fun cadenceIsHighWhenFailingClosedOnAStaleFix() {
        val e = evaluator(); e.fix(1_000.0)
        assertEquals(TripCadence.HIGH, TripCadence.select(e.evaluate(t + ZoneEvaluator.STALE_MS + 1)))
    }

    // preset and ids
    @Test fun theJarawaPresetIsSane() {
        val p = ZonePresets.JARAWA
        assertTrue(p.isValid()); assertEquals("jarawa", p.id); assertEquals(5_000, p.bufferM); assertTrue(p.enabled); assertEquals("jarawa", p.preset)
        assertEquals(listOf(p), ZonePresets.ALL)
        fun at(lat: Double, lon: Double) = ZoneEvaluator().also { it.setZones(listOf(p), t); it.onFix(ZoneFix(t, lat, lon, 10f), t); it.onFix(ZoneFix(t + 1, lat, lon, 10f), t + 1) }.evaluate(t).overall
        assertEquals(ZoneState.INSIDE, at(11.97, 92.72))      // the middle of the box
        assertEquals(ZoneState.INSIDE, at(11.78, 92.72))      // 2 km south of the box: inside the 5 km buffer
        assertEquals(ZoneState.INSIDE, at(11.80 - 4.9 / 111.19, 92.72))
        assertEquals(ZoneState.APPROACH, at(11.80 - 6.0 / 111.19, 92.72)) // 6 km: approach band (5 + 2 km)
        assertEquals(ZoneState.OUTSIDE, at(11.6234, 92.7265)) // Port Blair, about 20 km south of the edge
        assertEquals(ZoneState.OUTSIDE, at(11.9, 93.12))      // 30 km east of the edge
    }

    @Test fun zoneRecordIds() {
        assertEquals("zone:z1:3", ZonePause.pauseId("z1", 3)); assertTrue(ZonePause.isZoneRecord("zone:z1:3"))
        assertEquals("z1", ZonePause.zoneIdOf("zone:z1:3")); assertEquals("jarawa", ZonePause.zoneIdOf(ZonePause.pauseId("jarawa", 12)))
        assertFalse(ZonePause.isZoneRecord("1234-uuid")); assertNull(ZonePause.zoneIdOf("1234-uuid")); assertNull(ZonePause.zoneIdOf("zone:")); assertNull(ZonePause.zoneIdOf("zone:x"))
    }

    @Test fun zoneValidity() {
        assertTrue(zone.isValid())
        assertFalse(zone.copy(id = "").isValid()); assertFalse(zone.copy(id = "a:b").isValid()); assertFalse(zone.copy(name = " ").isValid())
        assertFalse(zone.copy(south = 10.01).isValid()); assertFalse(zone.copy(west = 21.0).isValid()); assertFalse(zone.copy(north = 91.0).isValid())
        assertFalse(zone.copy(bufferM = -1).isValid()); assertFalse(zone.copy(bufferM = PauseZone.MAX_BUFFER_M + 1).isValid()); assertFalse(zone.copy(south = Double.NaN).isValid())
        assertEquals(300, PauseZone.DEFAULT_BUFFER_M)
    }
}
