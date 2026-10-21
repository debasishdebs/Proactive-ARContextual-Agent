package com.rokid.context.core.model.context

import com.rokid.context.core.model.MotionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val T0 = 1_000_000L
private const val MIN = 60_000L

/** Fake clock plus helpers; every evaluation uses the rig clock, evidence is stamped at the clock. */
private class Rig(val f: ContextFusion = ContextFusion()) {
    var now = T0
    var d: ContextDecision = f.evaluate(now)

    fun send(vararg e: Evidence): ContextDecision { e.forEach(f::onEvidence); d = f.evaluate(now); return d }
    fun advance(ms: Long): ContextDecision {
        val end = now + ms
        while (now < end) { now = minOf(now + 5_000, end); d = f.evaluate(now) }
        return d
    }

    fun hint(s: MotionState, frac: Float = 0f) = send(Evidence.GlassesMotion(now, s, frac))
    fun ar(a: PhoneActivity, enter: Boolean = true) = send(Evidence.ActivityTransition(now, a, enter))
    fun steps(n: Int) = send(Evidence.Steps(now, n))
    fun speed(kmh: Float, precise: Boolean = true) = send(Evidence.Speed(now, kmh, precise))
    fun car(k: CarLinkKind, c: Boolean = true) = send(Evidence.CarLink(now, k, c))
    fun air(on: Boolean = true) = send(Evidence.Airplane(now, on))
    fun watch(s: WatchState, c: Float = 0.9f) = send(Evidence.WatchMotion(now, s, c))
    fun intent(l: IntentLabel, c: Float = 0.8f) = send(Evidence.Intent(now, l, c))
}

class ContextFusionTest {
    private val DRV = ContextState.VEHICLE_DRIVING
    private val PAX = ContextState.VEHICLE_PASSENGER

    @Test fun emptyIsUnknown() {
        val r = Rig()
        val d = r.advance(10 * MIN)
        assertEquals(ContextState.UNKNOWN, d.state); assertEquals(0f, d.confidence)
        assertTrue(d.reasons.isEmpty()); assertEquals(null, d.pDriver); assertFalse(d.vehicleSuspected)
    }

    @Test fun glassesWalkIsImmediate() {
        val r = Rig()
        val d = r.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, d.state)
        assertTrue(ContextReason.GLASSES_WALK in d.reasons)
        assertEquals(0.9f, d.confidence)
    }

    @Test fun walkingVetoesCarBt() {
        val r = Rig()
        r.car(CarLinkKind.CAR_CLASS_BT)
        r.advance(5 * MIN)
        assertEquals(DRV, r.d.state)            // the car BT alone is DRIVING (spec table)
        r.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, r.d.state)
        r.advance(MIN); r.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, r.advance(MIN).state)
    }

    @Test fun fastSpeedLiftsTheVeto() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.speed(60f); r.advance(5_000); r.speed(60f); r.hint(MotionState.WALKING)
        r.advance(24_000)
        assertNotEquals(DRV, r.d.state); assertEquals(ContextState.UNKNOWN, r.d.state)
        assertNotEquals(ContextState.WALKING, r.d.candidate)
        r.advance(1_000)
        assertTrue(r.d.state == PAX || r.d.state == DRV, r.d.state.name)
        assertTrue(ContextReason.SPEED in r.d.reasons)
    }

    @Test fun stepsAloneAreWalking() {
        val r = Rig()
        assertEquals(ContextState.WALKING, r.steps(70).state)
        assertTrue(ContextReason.STEPS in r.d.reasons)
        assertEquals(ContextState.UNKNOWN, Rig().steps(59).state)
    }

    @Test fun oldStepsExpire() {
        val r = Rig()
        r.steps(70)
        r.advance(91_000)
        assertNotEquals(ContextState.WALKING, r.d.candidate)
        assertFalse(ContextReason.STEPS in r.d.reasons)
    }

    @Test fun staleHintIgnored() {
        val r = Rig()
        r.hint(MotionState.WALKING)
        r.advance(91_000)
        assertNotEquals(ContextState.WALKING, r.d.candidate)
        assertFalse(ContextReason.GLASSES_WALK in r.d.reasons)
    }

    @Test fun phoneAndWatchWalkVetoToo() {
        assertEquals(ContextState.WALKING, Rig().apply { ar(PhoneActivity.WALKING) }.d.state)
        assertEquals(ContextState.WALKING, Rig().apply { ar(PhoneActivity.RUNNING) }.d.state)
        assertEquals(ContextState.WALKING, Rig().apply { watch(WatchState.WALKING) }.d.state)
        val r = Rig(); r.ar(PhoneActivity.WALKING); r.ar(PhoneActivity.WALKING, enter = false)
        assertNotEquals(ContextState.WALKING, r.d.candidate)
    }

    @Test fun androidAutoIsDrivingAfter30s() {
        val r = Rig()
        r.car(CarLinkKind.ANDROID_AUTO)
        r.advance(29_000)
        assertEquals(ContextState.UNKNOWN, r.d.state)
        assertTrue(r.d.vehicleSuspected)
        r.advance(1_000)
        assertEquals(DRV, r.d.state)
        assertEquals(0.85, r.d.pDriver!!, 0.01)
        assertEquals(0.85f * 0.95f, r.d.confidence, 0.01f)
        assertEquals(T0 + 30_000, r.d.sinceMs)
        assertTrue(ContextReason.ANDROID_AUTO in r.d.reasons)
    }

    // ---- spec 6.3 override table: one test per row
    private fun settled(kind: CarLinkKind?, ar: Boolean = false): Rig {
        val r = Rig()
        if (kind != null) r.car(kind)
        if (ar) r.ar(PhoneActivity.IN_VEHICLE)
        r.advance(30_000)
        return r
    }
    private fun Rig.twoImages(l: IntentLabel, c: Float = 0.8f) { intent(l, c); advance(10_000); intent(l, c) }

    @Test fun rowAndroidAuto() { val r = settled(CarLinkKind.ANDROID_AUTO); assertEquals(DRV, r.d.state); assertEquals(0.85, r.d.pDriver!!, 0.01) }

    @Test fun rowAaPlusOnePassengerStaysDriving() {
        val r = settled(CarLinkKind.ANDROID_AUTO)
        r.intent(IntentLabel.VEHICLE_PASSENGER, 0.8f); r.advance(2 * MIN)
        assertEquals(0.59, r.d.pDriver!!, 0.01); assertEquals(DRV, r.d.state)
    }

    @Test fun rowAaPlusTwoPassengerBecomesPassenger() {
        val r = settled(CarLinkKind.ANDROID_AUTO)
        r.twoImages(IntentLabel.VEHICLE_PASSENGER)
        assertEquals(0.26, r.d.pDriver!!, 0.01)
        r.advance(50_000); assertEquals(DRV, r.d.state)      // dwell not yet served (10 s + 50 s < 60 s from the flip)
        r.advance(15_000); assertEquals(PAX, r.d.state)
    }

    @Test fun rowMyCarPlusConfidentPassenger() {
        val r = settled(CarLinkKind.MY_CAR_BT)
        assertEquals(DRV, r.d.state); assertEquals(0.75, r.d.pDriver!!, 0.01)
        r.intent(IntentLabel.VEHICLE_PASSENGER, 0.9f)
        assertEquals(0.25, r.d.pDriver!!, 0.01)
        r.advance(61_000); assertEquals(PAX, r.d.state)
    }

    @Test fun rowMyCarPlusWeakerPassengerStaysDriving() {
        val r = settled(CarLinkKind.MY_CAR_BT)
        r.intent(IntentLabel.VEHICLE_PASSENGER, 0.8f); r.advance(3 * MIN)
        assertEquals(0.43, r.d.pDriver!!, 0.01); assertEquals(DRV, r.d.state)
    }

    @Test fun rowCarClassPlusPassengerIsPassenger() {
        val r = settled(CarLinkKind.CAR_CLASS_BT)
        r.intent(IntentLabel.VEHICLE_PASSENGER, 0.8f)
        assertEquals(0.20, r.d.pDriver!!, 0.01)
        r.advance(61_000); assertEquals(PAX, r.d.state)
    }

    @Test fun rowCarClassAloneIsDriving() {
        val r = settled(CarLinkKind.CAR_CLASS_BT)
        assertEquals(DRV, r.d.state); assertEquals(0.5, r.d.pDriver!!, 0.01)
        assertEquals(0.5f * 0.8f + 0.4f * 0f, r.d.confidence, 0.01f)   // max(p,1-p) 0.5 x 0.8 car-BT-only
    }

    @Test fun rowNoLinkActivityIsPassenger() {
        val r = settled(null, ar = true)
        assertEquals(PAX, r.d.state); assertEquals(0.15, r.d.pDriver!!, 0.01)
        assertEquals(0.85f * 0.95f, r.d.confidence, 0.01f)
    }

    @Test fun rowNoLinkOneDrivingStaysPassenger() {
        val r = settled(null, ar = true)
        r.intent(IntentLabel.DRIVING, 0.8f); r.advance(3 * MIN)
        assertEquals(0.41, r.d.pDriver!!, 0.01); assertEquals(PAX, r.d.state)
    }

    @Test fun rowNoLinkTwoDrivingIsDriving() {
        val r = settled(null, ar = true)
        r.twoImages(IntentLabel.DRIVING)
        assertEquals(0.74, r.d.pDriver!!, 0.01)
        r.advance(65_000); assertEquals(DRV, r.d.state)
    }

    @Test fun rowAaPlusWeakPassengerImageStaysDriving() {
        val r = settled(CarLinkKind.ANDROID_AUTO)
        r.intent(IntentLabel.VEHICLE_PASSENGER, 0.6f); r.advance(3 * MIN)
        assertEquals(0.79, r.d.pDriver!!, 0.01); assertEquals(DRV, r.d.state)
    }

    @Test fun btContradictedByTwoIndoorImages() {
        val r = Rig()
        r.car(CarLinkKind.CAR_CLASS_BT); r.advance(MIN)
        assertEquals(DRV, r.d.state)
        r.intent(IntentLabel.COMPUTER_WORK); r.advance(30_000); r.intent(IntentLabel.COMPUTER_WORK)
        assertTrue(ContextReason.BT_CONTRADICTED in r.d.reasons)
        assertEquals(ContextState.UNKNOWN, r.d.candidate)
        r.advance(65_000)
        assertEquals(ContextState.UNKNOWN, r.d.state)
        r.advance(10 * MIN)                                  // latched until that link disconnects
        assertEquals(ContextState.UNKNOWN, r.d.state)
        r.car(CarLinkKind.CAR_CLASS_BT, false); r.advance(5_000)
        r.car(CarLinkKind.CAR_CLASS_BT, true); r.advance(30_000)
        assertEquals(DRV, r.d.state)
    }

    @Test fun androidAutoIsNotContradictedByIndoorImages() {
        val r = Rig()
        r.car(CarLinkKind.ANDROID_AUTO); r.advance(MIN)
        r.intent(IntentLabel.COMPUTER_WORK); r.advance(10_000); r.intent(IntentLabel.COMPUTER_WORK)
        r.advance(3 * MIN)
        assertEquals(DRV, r.d.state)
        assertFalse(ContextReason.BT_CONTRADICTED in r.d.reasons)
    }

    @Test fun imageOnlyVehicle() {
        val r = Rig()
        r.intent(IntentLabel.VEHICLE_PASSENGER); r.advance(5 * MIN)
        assertEquals(ContextState.UNKNOWN, r.d.state); assertFalse(r.d.vehicleSuspected)
        val r2 = Rig()
        r2.intent(IntentLabel.VEHICLE_PASSENGER); r2.advance(2 * MIN); r2.intent(IntentLabel.VEHICLE_PASSENGER)
        assertTrue(r2.d.vehicleSuspected)
        r2.advance(30_000)
        assertEquals(PAX, r2.d.state)
        assertTrue(ContextReason.IMAGE_VEHICLE in r2.d.reasons)
        assertEquals(0.99f * 0.75f, r2.d.confidence, 0.02f)
    }

    @Test fun twoImagesTooFarApartAreNotAVehicle() {
        val r = Rig()
        r.intent(IntentLabel.VEHICLE_PASSENGER); r.advance(4 * MIN); r.intent(IntentLabel.VEHICLE_PASSENGER)
        r.advance(2 * MIN)
        assertEquals(ContextState.UNKNOWN, r.d.state)
    }

    @Test fun vehicleHoldBridgesAStop() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.advance(30_000)
        assertEquals(PAX, r.d.state)
        r.ar(PhoneActivity.IN_VEHICLE, enter = false)
        r.advance(179_000); assertEquals(PAX, r.d.state)
        r.advance(60_000); assertEquals(PAX, r.d.state)       // 239 s: candidate UNKNOWN only since ~185 s
        r.advance(11_000); assertEquals(ContextState.UNKNOWN, r.d.state)
    }

    @Test fun heldVehicleKeepsItsSubStateWhenAaDrops() {
        val r = settled(CarLinkKind.ANDROID_AUTO)
        r.car(CarLinkKind.ANDROID_AUTO, false)
        r.advance(170_000)
        assertEquals(DRV, r.d.state)                      // a USB glitch does not turn the driver into a passenger
    }

    @Test fun flightNeedsAirplaneAndSpeedOrImage() {
        val r = Rig()
        r.air(); r.advance(3 * MIN)
        assertNotEquals(ContextState.FLIGHT, r.d.state); assertTrue(r.d.vehicleSuspected)
        r.speed(800f); r.advance(119_000); assertNotEquals(ContextState.FLIGHT, r.d.state)
        r.advance(1_000); assertEquals(ContextState.FLIGHT, r.d.state)
        assertEquals(0.9f, r.d.confidence)

        val r2 = Rig(); r2.air(); r2.advance(3 * MIN); r2.intent(IntentLabel.FLIGHT_CABIN, 0.7f)
        r2.advance(2 * MIN); assertEquals(ContextState.FLIGHT, r2.d.state)
        assertTrue(ContextReason.IMAGE_FLIGHT in r2.d.reasons)

        val r3 = Rig(); r3.speed(400f); r3.advance(2 * MIN)
        assertEquals(ContextState.FLIGHT, r3.d.state)
    }

    @Test fun airplaneModeAtHomeIsNotAFlight() {
        val r = Rig()
        r.air(); r.hint(MotionState.STILL, 0.95f)
        r.advance(30 * MIN); r.hint(MotionState.STILL, 0.95f)
        assertNotEquals(ContextState.FLIGHT, r.d.state)
        val r2 = Rig(); r2.air(); r2.advance(3 * MIN); r2.intent(IntentLabel.SITTING_IDLE, 0.9f); r2.advance(5 * MIN)
        assertNotEquals(ContextState.FLIGHT, r2.d.state)
        val r3 = Rig(); r3.air(); r3.advance(3 * MIN); r3.speed(60f); r3.advance(3 * MIN)   // airplane + road speed is no flight
        assertNotEquals(ContextState.FLIGHT, r3.d.state)
        val r4 = Rig(); r4.air(); r4.advance(3 * MIN); r4.air(false); r4.speed(200f); r4.advance(3 * MIN)
        assertNotEquals(ContextState.FLIGHT, r4.d.state)   // airplane off again, 200 km/h without it: a train, not a flight
    }

    @Test fun airplaneOnForUnder2MinNeedsMore() {
        val r = Rig(); r.air(); r.advance(60_000); r.speed(200f)
        assertNotEquals(ContextState.FLIGHT, r.d.candidate)   // airplane on only 60 s: 200 km/h is not enough yet
        r.advance(65_000); r.speed(200f)
        assertEquals(ContextState.FLIGHT, r.d.candidate)
    }

    @Test fun restFromStillGlasses() {
        val r = Rig()
        r.hint(MotionState.STILL, 0.9f); r.advance(119_000); assertNotEquals(ContextState.REST, r.d.state)
        r.advance(1_000); assertEquals(ContextState.REST, r.d.state)
        assertEquals(0.9f, r.d.confidence, 0.001f); assertTrue(ContextReason.GLASSES_STILL in r.d.reasons)

        val r2 = Rig(); r2.hint(MotionState.STILL, 0.75f); r2.advance(110_000); r2.hint(MotionState.STILL, 0.75f); r2.advance(60_000)
        assertNotEquals(ContextState.REST, r2.d.state)

        val r3 = Rig(); r3.hint(MotionState.STILL, 0.75f); r3.intent(IntentLabel.SITTING_IDLE, 0.8f); r3.advance(60_000)
        r3.hint(MotionState.STILL, 0.75f); r3.intent(IntentLabel.SITTING_IDLE, 0.8f); r3.advance(61_000)
        assertEquals(ContextState.REST, r3.d.state); assertTrue(ContextReason.IMAGE_IDLE in r3.d.reasons)

        val r4 = Rig(); r4.hint(MotionState.STILL, 0.9f); r4.intent(IntentLabel.WORKOUT, 0.8f); r4.advance(60_000)
        r4.hint(MotionState.STILL, 0.9f); r4.advance(70_000)
        assertNotEquals(ContextState.REST, r4.d.state)

        val r5 = Rig(); r5.hint(MotionState.STILL, 0.9f); r5.ar(PhoneActivity.WALKING)
        assertEquals(ContextState.WALKING, r5.d.state); assertNotEquals(ContextState.REST, r5.d.candidate)
    }

    @Test fun watchAsleepIsRest() {
        val r = Rig(); r.watch(WatchState.ASLEEP); r.advance(2 * MIN)
        assertEquals(ContextState.REST, r.d.state); assertTrue(ContextReason.WATCH_ASLEEP in r.d.reasons)
    }

    @Test fun staleAsleepIgnoredWhileFreshMovingHint() {
        val r = Rig(); r.watch(WatchState.ASLEEP)
        repeat(6) { r.hint(MotionState.STILL, 0.4f); r.advance(30_000) }
        assertEquals(ContextState.UNKNOWN, r.d.state); assertFalse(ContextReason.WATCH_ASLEEP in r.d.reasons)
    }

    @Test fun asleepWithStillHintStillRest() {
        val r = Rig(); r.watch(WatchState.ASLEEP)
        repeat(6) { r.hint(MotionState.STILL, 0.95f); r.advance(30_000) }
        assertEquals(ContextState.REST, r.d.state); assertTrue(ContextReason.WATCH_ASLEEP in r.d.reasons)
    }

    @Test fun asleepWithStaleHintStillRest() {
        // a hint older than hintStillTtlMs says nothing: the watch alone decides
        val r = Rig(); r.hint(MotionState.STILL, 0.2f); r.advance(3 * MIN); r.watch(WatchState.ASLEEP); r.advance(2 * MIN)
        assertEquals(ContextState.REST, r.d.state)
    }

    @Test fun walkingExitNeeds60s() {
        val r = Rig()
        r.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, r.d.state)
        r.hint(MotionState.STILL, 0.1f)
        r.advance(59_000); assertEquals(ContextState.WALKING, r.d.state)
        r.advance(2_000); assertEquals(ContextState.UNKNOWN, r.d.state)
    }

    @Test fun vehicleSuspectedFlag() {
        assertTrue(Rig().apply { air() }.d.vehicleSuspected)
        assertFalse(Rig().d.vehicleSuspected)
        assertTrue(Rig().apply { ar(PhoneActivity.IN_VEHICLE) }.d.vehicleSuspected)
    }

    // ---- dangerous scenarios beyond the brief
    @Test fun stationaryGpsJitterIsNotAVehicle() {
        val r = Rig()
        for (k in listOf(0f, 3f, 7f, 12f, 4f, 24.9f)) { r.speed(k, precise = false); r.advance(20_000) }
        r.advance(2 * MIN)
        assertEquals(ContextState.UNKNOWN, r.d.state); assertFalse(r.d.vehicleSuspected)
    }

    @Test fun walkingBesideAParkedCarWithBtConnected() {
        val r = Rig()
        r.car(CarLinkKind.MY_CAR_BT)
        for (i in 0 until 10) { r.hint(MotionState.WALKING); r.steps(80); r.advance(30_000) }
        assertEquals(ContextState.WALKING, r.d.state)
        assertTrue(ContextReason.MY_CAR_BT in r.d.reasons)
    }

    @Test fun trainOrBusIsAPassengerNeverDriving() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.speed(90f)
        repeat(20) { r.advance(30_000); r.speed(90f); assertNotEquals(DRV, r.d.state) }
        assertEquals(PAX, r.d.state)
        r.intent(IntentLabel.DRIVING, 0.8f); r.advance(5 * MIN)
        assertEquals(PAX, r.d.state)                     // one image never flips it
    }

    @Test fun walkingInATrainAisleIsWalkingOnlyBelowSpeed() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.speed(90f); r.advance(5_000); r.speed(90f); r.advance(MIN)
        r.speed(90f); r.advance(5_000); r.speed(90f)
        r.hint(MotionState.WALKING)
        assertEquals(PAX, r.d.state)                     // fast lifts the veto
        r.advance(3 * MIN); r.hint(MotionState.WALKING) // speed expired (120 s): the veto returns
        assertEquals(ContextState.WALKING, r.d.state)
    }

    @Test fun staleEvidenceExpires() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.advance(MIN)
        assertEquals(PAX, r.d.state)
        r.advance(29 * MIN); assertEquals(PAX, r.d.state)
        r.advance(10 * MIN)                              // 30 min TTL, 180 s hold, 60 s dwell
        assertEquals(ContextState.UNKNOWN, r.d.state)

        val r2 = Rig(); r2.speed(60f); r2.ar(PhoneActivity.IN_VEHICLE, false); r2.advance(121_000)
        assertFalse(ContextReason.SPEED in r2.d.reasons)

        // intents older than 10 min no longer vote
        val r3 = settled(null, ar = true)
        r3.twoImages(IntentLabel.DRIVING); r3.advance(61_000); assertEquals(DRV, r3.d.state)
        r3.ar(PhoneActivity.IN_VEHICLE)                  // keep the vehicle fact alive
        r3.advance(11 * MIN); r3.ar(PhoneActivity.IN_VEHICLE)
        assertEquals(0.15, r3.d.pDriver!!, 0.01)
    }

    @Test fun smallBackwardsClockChangeIsHarmless() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.advance(MIN)
        val before = r.d
        val d = r.f.evaluate(r.now - 20_000)
        assertEquals(before.state, d.state); assertEquals(before.sinceMs, d.sinceMs)
    }

    @Test fun largeBackwardsClockJumpForgetsEverything() {
        val r = Rig()
        r.car(CarLinkKind.ANDROID_AUTO); r.advance(MIN)
        assertEquals(DRV, r.d.state)
        val d = r.f.evaluate(r.now - 3_600_000L)
        assertEquals(ContextState.UNKNOWN, d.state); assertFalse(d.vehicleSuspected)
        assertNotNull(d.sinceMs)
    }

    @Test fun flappingWalkHintStaysWalking() {
        val r = Rig()
        r.hint(MotionState.WALKING)
        val since = r.d.sinceMs
        repeat(30) { i ->
            r.advance(20_000)
            if (i % 2 == 0) r.hint(MotionState.STILL, 0.1f) else r.hint(MotionState.WALKING)
            assertEquals(ContextState.WALKING, r.d.state)
            assertEquals(since, r.d.sinceMs)
        }
    }

    @Test fun flappingSpeedInsideARealVehicleStaysVehicle() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.advance(30_000)
        repeat(20) { i ->
            r.speed(if (i % 2 == 0) 0f else 70f); r.advance(20_000)
            assertEquals(PAX, r.d.state)
        }
    }

    @Test fun flappingDriverVotesNeverCommitAFlip() {
        val r = settled(CarLinkKind.ANDROID_AUTO)
        assertEquals(DRV, r.d.state)
        // alternate: passenger pair then (drive pair) every 30 s; the candidate never holds 60 s
        repeat(6) {
            r.twoImages(IntentLabel.VEHICLE_PASSENGER); r.advance(20_000)
            r.twoImages(IntentLabel.DRIVING); r.advance(20_000)
        }
        assertEquals(DRV, r.d.state)
    }

    @Test fun duplicateCarConnectDoesNotResetTheContradictionClock() {
        val r = Rig()
        r.car(CarLinkKind.CAR_CLASS_BT); r.advance(MIN)
        r.intent(IntentLabel.COMPUTER_WORK); r.advance(5_000); r.intent(IntentLabel.COMPUTER_WORK)
        r.car(CarLinkKind.CAR_CLASS_BT)                  // a repeated "connected" broadcast
        assertTrue(ContextReason.BT_CONTRADICTED in r.d.reasons)
    }

    @Test fun evidenceWithoutEvaluateThenFirstEvaluateWorks() {
        val f = ContextFusion()
        f.onEvidence(Evidence.CarLink(T0, CarLinkKind.ANDROID_AUTO, true))
        assertEquals(ContextState.UNKNOWN, f.evaluate(T0).state)
        assertEquals(DRV, f.evaluate(T0 + 30_000).state)
    }

    // ---- fix round 1
    private fun Rig.old(e: Evidence) { f.onEvidence(e); d = f.evaluate(now) }

    @Test fun disconnectAfterBackwardsClockStepIsApplied() {
        val r = Rig()
        r.car(CarLinkKind.ANDROID_AUTO); r.advance(MIN)
        assertEquals(DRV, r.d.state)
        r.d = r.f.evaluate(r.now - 20_000)                                   // small backwards step
        r.old(Evidence.CarLink(T0 - 5_000, CarLinkKind.ANDROID_AUTO, false)) // stamped before the connect
        assertFalse(ContextReason.ANDROID_AUTO in r.d.reasons)
        r.advance(5 * MIN)
        assertEquals(ContextState.UNKNOWN, r.d.state)
    }

    @Test fun airplaneOffAfterBackwardsClockStepIsApplied() {
        val r = Rig()
        r.air(); r.advance(MIN)
        r.old(Evidence.Airplane(T0 - 5_000, false))
        assertFalse(r.d.vehicleSuspected)
    }

    @Test fun activityExitAfterBackwardsClockStepIsApplied() {
        val r = Rig()
        r.ar(PhoneActivity.IN_VEHICLE); r.advance(MIN)
        r.old(Evidence.ActivityTransition(T0 - 5_000, PhoneActivity.IN_VEHICLE, false))
        r.advance(5 * MIN)
        assertEquals(ContextState.UNKNOWN, r.d.state)
    }

    @Test fun staleDuplicatesDoNotRetrigger() {
        val r = Rig()
        r.air(); r.advance(3 * MIN)
        r.old(Evidence.Airplane(T0 - 50_000, true))          // duplicate "on": the airplane-on clock is kept
        r.speed(200f)
        assertEquals(ContextState.FLIGHT, r.d.candidate)
        val r2 = Rig()
        r2.car(CarLinkKind.ANDROID_AUTO, false); r2.old(Evidence.CarLink(T0 - 9_000, CarLinkKind.ANDROID_AUTO, false))
        assertFalse(r2.d.vehicleSuspected)
    }

    @Test fun singleFastSpeedSpikeChangesNothing() {
        val r = Rig()
        r.hint(MotionState.WALKING); r.speed(60f)
        assertEquals(ContextState.WALKING, r.d.state)
        assertFalse(ContextReason.SPEED in r.d.reasons)
        r.advance(2 * MIN); r.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, r.d.state)
        val r2 = Rig(); r2.speed(60f); r2.advance(3 * MIN)
        assertEquals(ContextState.UNKNOWN, r2.d.state); assertFalse(r2.d.vehicleSuspected)
    }

    @Test fun twoFastReadingsWithin15sLiftTheVeto() {
        val r = Rig()
        r.hint(MotionState.WALKING); r.speed(60f); r.advance(10_000); r.speed(55f)
        assertTrue(ContextReason.SPEED in r.d.reasons)
        assertNotEquals(ContextState.WALKING, r.d.candidate)
        val r2 = Rig()
        r2.hint(MotionState.WALKING); r2.speed(60f); r2.advance(50_000); r2.hint(MotionState.WALKING); r2.speed(60f)
        assertEquals(ContextState.WALKING, r2.d.state)       // 50 s apart: not confirmed
        assertFalse(ContextReason.SPEED in r2.d.reasons)
    }

    @Test fun spikeThenWalkingActivityStaysWalking() {
        val r = Rig()
        r.speed(60f); r.ar(PhoneActivity.WALKING)
        assertEquals(ContextState.WALKING, r.d.state)
        r.advance(2 * MIN)
        assertEquals(ContextState.WALKING, r.d.state)
    }

    @Test fun heldVehicleNeverStartsANewEpisode() {
        val r = settled(CarLinkKind.ANDROID_AUTO)
        assertEquals(DRV, r.d.state)
        r.car(CarLinkKind.ANDROID_AUTO, false)
        r.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, r.d.state)
        r.advance(30_000)
        r.hint(MotionState.STILL, 0.1f)                      // stops walking, still inside the 180 s hold
        repeat(24) {
            r.advance(5_000)
            assertFalse(r.d.state == DRV || r.d.state == PAX, "state ${r.d.state}")
            assertFalse(r.d.candidate == DRV || r.d.candidate == PAX, "cand ${r.d.candidate}")
        }
    }

    @Test fun consecutiveFastReadings30sApartConfirm() {
        val r = Rig()
        r.hint(MotionState.WALKING); r.speed(60f); r.advance(30_000); r.hint(MotionState.WALKING); r.speed(60f)
        assertTrue(ContextReason.SPEED in r.d.reasons)
        assertNotEquals(ContextState.WALKING, r.d.candidate)
    }

    @Test fun aSlowReadingBetweenTwoFastOnesResetsThePair() {
        val r = Rig()
        r.hint(MotionState.WALKING); r.speed(60f); r.advance(20_000); r.speed(10f); r.advance(20_000)
        r.hint(MotionState.WALKING); r.speed(60f)
        assertFalse(ContextReason.SPEED in r.d.reasons)
        assertEquals(ContextState.WALKING, r.d.state)
        r.advance(20_000); r.hint(MotionState.WALKING); r.speed(60f)      // now two consecutive fast ones
        assertTrue(ContextReason.SPEED in r.d.reasons)
    }
}
