package com.rokid.context.phone.context

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextFusion
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.FusionParams
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.core.model.context.WatchState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ContextHubTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val six = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "watch_app", "image_intent")

    private class FakeSource(override val id: String, var avail: Boolean = true, var startOk: Boolean = true) : ContextSource {
        var starts = 0; var stops = 0
        override fun available() = avail
        override fun start(): Boolean { starts++; onStart?.invoke(); return startOk }
        override fun stop() { stops++ }
        override var events = 0
        var onStart: (() -> Unit)? = null
    }

    private class FakeSpeed : SpeedControl {
        val calls = ArrayList<Boolean>()
        override var requesting = false
        override fun setWanted(on: Boolean) { calls += on }
    }

    private inner class Fx(val ts: TestScope, fusion: ContextFusion? = null, overrides: kotlinx.coroutines.flow.StateFlow<Map<String, Double>>? = null) {
        val t0 = 1_793_000_000_000L
        val clock: () -> Long = { t0 + ts.testScheduler.currentTime }
        val prefs = ctx.getSharedPreferences("hub_${counter.incrementAndGet()}", 0).also { it.edit().clear().commit() }
        val settings = ContextSourceSettings(prefs)
        val registry = ContextSourceRegistry()
        val logs = ArrayList<String>()
        val stats = ContextHubStats(clock)
        var watchOffs = 0
        val seen = ArrayList<Evidence>()
        var monoNow = 0L
        val hub = ContextHub(ts.backgroundScope, settings, registry, fusion = fusion ?: ContextFusion(FusionParams(watchTtlMs = 1_500_000)), clock = clock, stats = stats, log = { logs += it }, onWatchOff = { watchOffs++ }, overrides = overrides, onAccepted = { seen += it }, mono = { monoNow })
        val msgs = ArrayList<MotionContextMessage?>()
        init { ts.backgroundScope.launch(UnconfinedTestDispatcher(ts.testScheduler)) { hub.message.collect { msgs += it } } }
        fun start() { hub.start(); ts.runCurrent() }
        fun on(vararg ids: String) { ids.forEach { settings.setEnabled(it, true) }; ts.runCurrent() }
        fun off(vararg ids: String) { ids.forEach { settings.setEnabled(it, false) }; ts.runCurrent() }
        fun reg(id: String, avail: Boolean = true, startOk: Boolean = true) = FakeSource(id, avail, startOk).also { registry.register(it) }
        fun adv(ms: Long) { ts.advanceTimeBy(ms); ts.runCurrent() }
        fun accept(e: Evidence) { hub.accept(e); ts.runCurrent() }
        fun hint(s: MotionState) { hub.onMotionHint(MotionHint(s, clock(), stillFrac5m = if (s == MotionState.STILL) 0.95f else 0f)); ts.runCurrent() }
    }

    private companion object { val counter = AtomicInteger() }

    @Test fun noSourceOnMeansNoMessage() = runTest {
        val f = Fx(this); f.start()
        assertNull(f.hub.message.value)
        assertTrue(f.msgs.filterNotNull().isEmpty())
        assertTrue(f.hub.status.value.values.all { it == SourceStatus.OFF })
    }

    @Test fun emptyRegistryWithAllSixOnIsUnavailableAndSilent() = runTest {
        val f = Fx(this); f.on(*six.toTypedArray()); f.start(); f.adv(15_000)
        six.forEach { assertEquals(it, SourceStatus.UNAVAILABLE, f.hub.status.value[it]) }
        assertNull(f.hub.message.value)
        assertTrue(f.msgs.filterNotNull().isEmpty())
    }

    @Test fun sourceRegisteredAfterStartIsStartedOnce() = runTest {
        val f = Fx(this); f.on("watch_app"); f.start()
        assertEquals(SourceStatus.UNAVAILABLE, f.hub.status.value["watch_app"])
        val s = f.reg("watch_app"); f.ts.runCurrent()
        assertEquals(1, s.starts)
        assertEquals(SourceStatus.ON, f.hub.status.value["watch_app"])
        f.adv(30_000)
        assertEquals(1, s.starts)
    }

    @Test fun unavailableSourceIsNeverStarted() = runTest {
        val f = Fx(this); val s = f.reg("phone_steps", avail = false); f.on("phone_steps"); f.start()
        assertEquals(0, s.starts)
        assertEquals(SourceStatus.UNAVAILABLE, f.hub.status.value["phone_steps"])
    }

    @Test fun failedStartIsFailedAndMessageStaysNull() = runTest {
        val f = Fx(this); val s = f.reg("phone_car", startOk = false); f.on("phone_car"); f.start()
        assertEquals(1, s.starts)
        assertEquals(SourceStatus.FAILED, f.hub.status.value["phone_car"])
        f.adv(30_000)
        assertEquals("not retried on its own", 1, s.starts)
        assertNull(f.hub.message.value)
    }

    @Test fun switchingOffStopsTheSource() = runTest {
        val f = Fx(this); val s = f.reg("phone_car"); f.on("phone_car"); f.start()
        assertEquals(SourceStatus.ON, f.hub.status.value["phone_car"])
        f.off("phone_car")
        assertEquals(1, s.stops)
        assertEquals(SourceStatus.OFF, f.hub.status.value["phone_car"])
    }

    @Test fun phoneAirplaneIsWantedByActivityOrSpeed() = runTest {
        val f = Fx(this); val air = f.reg("phone_airplane"); f.start()
        assertEquals(0, air.starts)
        f.on("phone_speed"); assertEquals(1, air.starts)
        f.on("phone_activity"); f.off("phone_speed"); assertEquals("still wanted by activity", 0, air.stops)
        f.off("phone_activity"); assertEquals(1, air.stops)
    }

    @Test fun nullSpeedControlWithVehicleSuspectedDoesNotCrash() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.on("phone_car", "phone_speed"); f.start()
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(40_000)
        assertEquals(ContextState.VEHICLE_DRIVING, f.hub.decision.value.state)
    }

    @Test fun watchWalkingAppliesAtOnce() = runTest {
        val f = Fx(this); f.reg("watch_app"); f.on("watch_app"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        assertEquals(ContextState.WALKING, f.hub.message.value!!.state)
    }

    @Test fun watchInVehicleIsPassengerNeverDriving() = runTest {
        val f = Fx(this); f.reg("watch_app"); f.on("watch_app"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.IN_VEHICLE, 0.9f)); f.adv(31_000)
        assertEquals(ContextState.VEHICLE_PASSENGER, f.hub.message.value!!.state)
        assertTrue(f.msgs.filterNotNull().none { it.state == ContextState.VEHICLE_DRIVING })
    }

    /** Two fresh 22 km/h readings 30 s apart: a vehicle only when "fast" is below 22 (default 25). */
    private fun slowFast(f: Fx) { f.accept(Evidence.Speed(f.clock(), 22f, true)); f.adv(30_000); f.accept(Evidence.Speed(f.clock(), 22f, true)); f.adv(40_000) }

    @Test fun withNoOverridesTwoReadingsAt22AreNotAVehicle() = runTest {
        val f = Fx(this, overrides = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())); f.reg("phone_speed"); f.on("phone_speed"); f.start()
        slowFast(f)
        assertTrue(f.msgs.filterNotNull().none { it.state == ContextState.VEHICLE_DRIVING || it.state == ContextState.VEHICLE_PASSENGER })
    }

    @Test fun aLoweredFastThresholdMakesTheSameReadingsAVehicleWithoutARestart() = runTest {
        val o = kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, Double>())
        val f = Fx(this, overrides = o); f.reg("phone_speed"); f.on("phone_speed"); f.start()
        o.value = mapOf("fastKmh" to 20.0); f.ts.runCurrent()
        slowFast(f)
        assertTrue(f.msgs.filterNotNull().any { it.state == ContextState.VEHICLE_DRIVING || it.state == ContextState.VEHICLE_PASSENGER })
        assertTrue(f.logs.any { it == "fusion settings applied" })
    }

    @Test fun overridesPresentAtConstructionApplyFromTheFirstEvaluation() = runTest {
        val f = Fx(this, overrides = kotlinx.coroutines.flow.MutableStateFlow(mapOf("fastKmh" to 20.0))); f.reg("phone_speed"); f.on("phone_speed"); f.start()
        slowFast(f)
        assertTrue(f.msgs.filterNotNull().any { it.state == ContextState.VEHICLE_DRIVING || it.state == ContextState.VEHICLE_PASSENGER })
    }

    @Test fun aGlassesHintCarriesTheClassifierHashToTheStatus() = runTest {
        val f = Fx(this); f.start()
        f.hub.onMotionHint(MotionHint(MotionState.STILL, f.clock(), stillFrac5m = 0.9f, classifierHash = "0123456789ab"))
        assertEquals("0123456789ab", GlassesClassifierStatus.reported.value)
        f.hub.onMotionHint(MotionHint(MotionState.STILL, f.clock(), stillFrac5m = 0.9f))
        assertEquals("an old glasses hint without a hash leaves the last report", "0123456789ab", GlassesClassifierStatus.reported.value)
    }

    @Test fun watchAsleepIsRestWithZeroConfidenceAfterTwoMinutes() = runTest {
        val f = Fx(this); f.reg("watch_app"); f.on("watch_app"); f.start()
        // a glasses hint whose still fraction is unknown (-1) makes the engine's REST confidence negative; the hub clamps it
        f.hub.onMotionHint(MotionHint(MotionState.STILL, f.clock(), stillFrac5m = -1f))
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.ASLEEP, 0.9f)); f.adv(121_000)
        f.hub.onMotionHint(MotionHint(MotionState.STILL, f.clock(), stillFrac5m = -1f)); f.adv(1_000)
        val m = f.hub.message.value!!
        assertEquals(ContextState.REST, m.state)
        assertEquals(0f, m.confidence, 0f)
        assertTrue(f.msgs.filterNotNull().all { it.confidence in 0f..1f })
    }

    @Test fun watchStillIsUnknown() = runTest {
        val f = Fx(this); f.reg("watch_app"); f.on("watch_app"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.STILL, 0.9f)); f.adv(130_000)
        assertEquals(ContextState.UNKNOWN, f.hub.message.value!!.state)
    }

    @Test fun hintsAloneUpdateDecisionButSendNothing() = runTest {
        val f = Fx(this); f.start()
        f.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, f.hub.decision.value.state)
        assertNull(f.hub.message.value)
        assertTrue(f.msgs.filterNotNull().isEmpty())
        assertEquals(1L, f.stats.snapshot()["hints"])
    }

    @Test fun hintWalkingGivesWalkingMessageWhileASourceRuns() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.on("phone_car"); f.start()
        f.hint(MotionState.WALKING)
        assertEquals(ContextState.WALKING, f.hub.message.value!!.state)
    }

    @Test fun androidAutoGivesDrivingAfterThirtySeconds() = runTest {
        val f = Fx(this); val car = f.reg("phone_car"); f.start()
        assertNull(f.hub.message.value)
        f.on("phone_car"); assertEquals(1, car.starts)
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true))
        f.adv(31_000)
        val m = f.hub.message.value!!
        assertEquals(ContextState.VEHICLE_DRIVING, m.state)
        assertTrue("ANDROID_AUTO" in m.reasons)
    }

    @Test fun speedControlFollowsSwitchAndVehicleSuspicion() = runTest {
        val f = Fx(this); val speed = FakeSpeed(); f.registry.speed = speed
        f.reg("phone_car"); f.on("phone_car", "phone_speed"); f.start()
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(31_000)
        assertTrue(speed.calls.last()); assertTrue(f.hub.speedWanted)
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, false)); f.adv(300_000)
        assertFalse(speed.calls.last()); assertFalse(f.hub.speedWanted)
    }

    @Test fun locationSecondsCountOnlyWhileTheShellIsRequesting() = runTest {
        val f = Fx(this); val speed = FakeSpeed(); f.registry.speed = speed
        f.reg("phone_car"); f.on("phone_car", "phone_speed"); f.start()
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(31_000)
        assertTrue(f.hub.speedWanted)
        f.adv(100_000)
        assertTrue("wanted but not requested: no location time", f.stats.line(ContextState.UNKNOWN).contains("locOnS=0 "))
        speed.requesting = true; f.adv(100_000)
        assertFalse(f.stats.line(ContextState.UNKNOWN).contains("locOnS=0 "))
    }

    @Test fun anAsyncFailureReportedBySourceMarksItFailedAndStopsIt() = runTest {
        val f = Fx(this); var fail: (() -> Unit)? = null
        val s = f.reg("phone_activity"); s.onStart = { fail = f.hub.failFor("phone_activity") }
        f.on("phone_activity"); f.start()
        assertEquals(SourceStatus.ON, f.hub.status.value["phone_activity"])
        fail!!.invoke(); f.ts.runCurrent()
        assertEquals(SourceStatus.FAILED, f.hub.status.value["phone_activity"]); assertEquals(1, s.stops)
        f.off("phone_activity"); f.on("phone_activity")
        assertEquals("a toggle starts it again", 2, s.starts)
        fail!!.invoke(); f.ts.runCurrent() // the new run's callback is live
        assertEquals(SourceStatus.FAILED, f.hub.status.value["phone_activity"]); assertEquals(2, s.stops)
    }

    @Test fun aStaleRunsFailureIsIgnored() = runTest {
        val f = Fx(this); val cbs = ArrayList<() -> Unit>()
        val s = f.reg("phone_activity"); s.onStart = { cbs += f.hub.failFor("phone_activity") }
        f.on("phone_activity"); f.start(); f.off("phone_activity"); f.on("phone_activity")
        cbs[0].invoke(); f.ts.runCurrent()
        assertEquals(SourceStatus.ON, f.hub.status.value["phone_activity"])
    }

    @Test fun applyToCaptureFlipsAtOnce() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.on("phone_car"); f.start()
        f.hint(MotionState.WALKING)
        assertTrue(f.hub.message.value!!.apply)
        f.settings.applyToCapture = false; f.ts.runCurrent()
        assertFalse(f.hub.message.value!!.apply)
        assertEquals(ContextState.WALKING, f.hub.message.value!!.state)
        f.settings.applyToCapture = true; f.ts.runCurrent()
        assertTrue(f.hub.message.value!!.apply)
    }

    @Test fun resendsEverySixtySecondsWithNewAtMs() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.on("phone_car"); f.start()
        f.hint(MotionState.WALKING)
        val first = f.hub.message.value!!
        val n = f.msgs.size
        f.adv(30_000); f.hint(MotionState.WALKING) // keep the hint fresh (90 s TTL)
        assertEquals(n, f.msgs.size)
        f.adv(31_000)
        assertTrue(f.msgs.size > n)
        assertTrue(f.hub.message.value!!.atMs > first.atMs)
        assertEquals(first.sinceMs, f.hub.message.value!!.sinceMs)
    }

    @Test fun revokingTheLastSourceSendsUnknownThenNull() = runTest {
        val f = Fx(this); val car = f.reg("phone_car"); f.on("phone_car"); f.start()
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(31_000)
        assertEquals(ContextState.VEHICLE_DRIVING, f.hub.message.value!!.state)
        val before = f.msgs.size
        f.off("phone_car")
        assertEquals(1, car.stops)
        val tail = f.msgs.drop(before)
        assertEquals(2, tail.size)
        assertEquals(ContextState.UNKNOWN, tail[0]!!.state); assertTrue(tail[0]!!.reasons.isEmpty())
        assertNull(tail[1])
        // a late callback from the stopped source is ignored
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(60_000)
        assertNull(f.hub.message.value)
        assertEquals(ContextState.UNKNOWN, f.hub.decision.value.state)
    }

    @Test fun revokingOneSourceClearsOnlyItsEvidenceAndKeepsTheRest() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.reg("watch_app"); f.on("phone_car", "watch_app"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(60_000)
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        f.off("phone_car")
        assertEquals("watch walking still stands after the rebuild", ContextState.WALKING, f.hub.message.value!!.state)
        assertFalse("ANDROID_AUTO" in f.hub.message.value!!.reasons)
    }

    @Test fun theHubKeepsTheRecentWatchValuesAndForgetsThemWhenTheWatchSourceIsSwitchedOff() = runTest {
        val f = Fx(this); f.reg("watch_app"); f.on("watch_app"); f.start()
        val t = f.clock()
        f.accept(Evidence.WatchMotion(t, WatchState.WALKING, 0.9f)); f.adv(40_000)
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.RUNNING, 0.8f))
        assertEquals(listOf(WatchState.WALKING, WatchState.RUNNING), f.hub.watchRing.recent(0, Long.MAX_VALUE).map { it.state })
        assertEquals("the window is honoured", listOf(WatchState.WALKING), f.hub.watchRing.recent(t, t + 1).map { it.state })
        f.off("watch_app")
        assertTrue("a source switched off forgets what it gave", f.hub.watchRing.recent(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun switchingTheWatchSourceOffTellsTheOwnersOfItsValuesAndNoOtherSourceDoes() = runTest {
        val f = Fx(this); f.reg("watch_app"); f.reg("phone_car"); f.on("watch_app", "phone_car"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        f.off("phone_car")
        assertEquals("another source going off is not the watch", 0, f.watchOffs)
        f.off("watch_app")
        assertEquals(1, f.watchOffs)
        assertTrue(f.hub.watchRing.recent(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun anotherSourcesEvidenceAndAnUnrunningWatchLeaveTheRingEmpty() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.on("phone_car"); f.start()
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true))
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f)) // the watch source is not running: the hub drops it
        assertTrue(f.hub.watchRing.recent(0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun aRunOneSinkIsDroppedAfterARestartAndRunTwoIsAccepted() = runTest {
        val f = Fx(this); val car = f.reg("phone_car")
        val sinks = ArrayList<EvidenceSink>()
        car.onStart = { sinks += f.hub.sinkFor("phone_car") }
        f.on("phone_car"); f.start()
        assertEquals(1, sinks.size)
        f.off("phone_car"); f.on("phone_car")
        assertEquals(2, sinks.size)
        sinks[0].accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.ts.runCurrent()
        assertEquals("the old run's callback is dropped", 0L, f.stats.snapshot()["aa"])
        sinks[1].accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.ts.runCurrent()
        assertEquals(1L, f.stats.snapshot()["aa"])
    }

    @Test fun aSinkTakenWhileTheSourceIsNotRunningIsANoOp() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.start()
        f.hub.sinkFor("phone_car").accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.ts.runCurrent()
        f.on("phone_car"); f.adv(1_000)
        assertEquals(0L, f.stats.snapshot()["aa"])
    }

    @Test fun evidenceEmittedInsideStartIsAccepted() = runTest {
        val f = Fx(this); val w = f.reg("watch_app")
        w.onStart = { f.hub.sinkFor("watch_app").accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f)) }
        f.on("watch_app"); f.start()
        assertEquals(1L, f.stats.snapshot()["watch"])
        assertEquals(ContextState.WALKING, f.hub.message.value!!.state)
    }

    @Test fun startIsIdempotentAndStopStopsEverythingThenRestartRestarts() = runTest {
        val f = Fx(this); val car = f.reg("phone_car"); f.on("phone_car"); f.start()
        f.hub.start(); f.ts.runCurrent()
        assertEquals(1, car.starts)
        f.hint(MotionState.WALKING)
        f.hub.stop(); f.ts.runCurrent()
        assertEquals(1, car.stops)
        assertNull(f.hub.message.value)
        f.hub.stop(); f.ts.runCurrent(); assertEquals(1, car.stops)
        val n = f.msgs.size
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(120_000)
        assertEquals("no further sends", n, f.msgs.size)
        f.hub.start(); f.ts.runCurrent()
        assertEquals(2, car.starts)
        assertEquals(SourceStatus.ON, f.hub.status.value["phone_car"])
    }

    @Test fun statsLineIsLoggedEveryTenMinutesAndCarriesOnlyCountsAndEnumNames() = runTest {
        val f = Fx(this); f.start(); f.adv(601_000)
        val line = f.logs.single { it.startsWith("ctxhub v=1 state=") }
        assertTrue(line, Regex("""^ctxhub v=1( \w+=[\w>-]+)+$""").matches(line))
    }

    // Codex v3 r3 P1-1: the receipt time never makes a delayed hint fresh calm evidence
    private fun glassesHints(f: Fx) = f.seen.filterIsInstance<Evidence.GlassesMotion>()
    @Test fun aDelayedHintCarriesItsAgeAndAnUnknownOneCarriesNone() = runTest {
        val f = Fx(this); f.start()
        fun send(g: Long, mono: Long, session: Long = 900L, st: MotionState = MotionState.WALKING) { f.monoNow = mono; f.hub.onMotionHint(MotionHint(st, g, sessionStartMs = session)); f.ts.runCurrent() }
        send(1_000_000, 10_000)                       // baseline: age unknown
        send(1_060_000, 70_000)                       // on time
        send(1_120_000, 150_000)                      // 20 s late
        send(1_180_000, 130_000 + 300_000)            // 4 min 10 s late: older than 2 min
        send(1_240_000, 310_000 + 60_000, session = 0) // no session: unknown
        assertEquals(listOf<Long?>(null, 0L, 20_000L, null, null), glassesHints(f).map { it.ageMs })
        assertEquals("fusion still gets the receipt time", f.clock(), glassesHints(f).first().atMs)
    }
    @Test fun aHintFromAnotherSessionRestartsTheBaselineSoItIsUnknown() = runTest {
        val f = Fx(this); f.start()
        fun send(g: Long, mono: Long, session: Long) { f.monoNow = mono; f.hub.onMotionHint(MotionHint(MotionState.STILL, g, stillFrac5m = 0.95f, sessionStartMs = session)); f.ts.runCurrent() }
        send(1_000_000, 10_000, 900); send(1_060_000, 70_000, 900); send(5_000, 80_000, 901); send(65_000, 140_000, 901)
        assertEquals(listOf<Long?>(null, 0L, null, 0L), glassesHints(f).map { it.ageMs })
    }

    @Test fun aFloodIsBoundedDropOldestAndCounted() = runTest {
        val f = Fx(this); f.start()
        repeat(2_000) { f.hub.onMotionHint(MotionHint(MotionState.WALKING, f.clock())) }
        f.ts.runCurrent()
        val snap = f.stats.snapshot()
        assertEquals(2_000L - ContextHub.MAX_PENDING, snap["dropped"])
        assertEquals(ContextHub.MAX_PENDING.toLong(), snap["hints"])
        assertEquals(ContextState.WALKING, f.hub.decision.value.state)
    }

    @Test fun concurrentIntakeWithSettingsTogglesMatchesAReorderedSerialReplay() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.reg("watch_app"); f.on("watch_app"); f.start()
        val at = f.clock()
        // Thread i sends states of its own with distinct stamps: the engine keeps the newest stamp, whatever the arrival order.
        fun ev(i: Int, k: Int) = Evidence.WatchMotion(at + i * 1_000L + k, if (i % 2 == 0) WatchState.WALKING else WatchState.IN_VEHICLE, 0.9f)
        val threads = (1..8).map { i -> Thread { repeat(100) { k -> f.hub.accept(ev(i, k)) } } }
        threads.forEach { it.start() }
        repeat(100) { f.settings.setEnabled("phone_car", it % 2 == 0); Thread.yield() }
        threads.forEach { it.join() }
        f.settings.setEnabled("phone_car", false)
        f.ts.runCurrent(); f.adv(40_000)
        val ref = ContextFusion(FusionParams(watchTtlMs = 1_500_000))
        val all = (1..8).flatMap { i -> (0 until 100).map { k -> ev(i, k) } }.reversed()
        all.forEach { ref.onEvidence(it) }
        ref.evaluate(at); val expected = ref.evaluate(f.clock())
        assertEquals("800 < MAX_PENDING: nothing dropped", 800L, f.stats.snapshot()["watch"])
        assertEquals(expected.state, f.hub.decision.value.state)
        assertEquals(expected.reasons, f.hub.decision.value.reasons)
        assertEquals(ContextState.WALKING, f.hub.decision.value.state)
    }
}
