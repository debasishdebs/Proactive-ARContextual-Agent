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
    private val six = listOf("phone_activity", "phone_steps", "phone_speed", "phone_car", "watch_livefit", "image_intent")

    private class FakeSource(override val id: String, var avail: Boolean = true, var startOk: Boolean = true) : ContextSource {
        var starts = 0; var stops = 0
        override fun available() = avail
        override fun start(): Boolean { starts++; return startOk }
        override fun stop() { stops++ }
        override var events = 0
    }

    private class FakeSpeed : SpeedControl {
        val calls = ArrayList<Boolean>()
        override fun setWanted(on: Boolean) { calls += on }
    }

    private inner class Fx(val ts: TestScope, fusion: ContextFusion? = null) {
        val t0 = 1_793_000_000_000L
        val clock: () -> Long = { t0 + ts.testScheduler.currentTime }
        val prefs = ctx.getSharedPreferences("hub_${counter.incrementAndGet()}", 0).also { it.edit().clear().commit() }
        val settings = ContextSourceSettings(prefs)
        val registry = ContextSourceRegistry()
        val logs = ArrayList<String>()
        val stats = ContextHubStats(clock)
        val hub = ContextHub(ts.backgroundScope, settings, registry, fusion = fusion ?: ContextFusion(FusionParams(watchTtlMs = 1_500_000)), clock = clock, stats = stats, log = { logs += it })
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
        val f = Fx(this); f.on("watch_livefit"); f.start()
        assertEquals(SourceStatus.UNAVAILABLE, f.hub.status.value["watch_livefit"])
        val s = f.reg("watch_livefit"); f.ts.runCurrent()
        assertEquals(1, s.starts)
        assertEquals(SourceStatus.ON, f.hub.status.value["watch_livefit"])
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
        val f = Fx(this); f.reg("watch_livefit"); f.on("watch_livefit"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        assertEquals(ContextState.WALKING, f.hub.message.value!!.state)
    }

    @Test fun watchInVehicleIsPassengerNeverDriving() = runTest {
        val f = Fx(this); f.reg("watch_livefit"); f.on("watch_livefit"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.IN_VEHICLE, 0.9f)); f.adv(31_000)
        assertEquals(ContextState.VEHICLE_PASSENGER, f.hub.message.value!!.state)
        assertTrue(f.msgs.filterNotNull().none { it.state == ContextState.VEHICLE_DRIVING })
    }

    @Test fun watchAsleepIsRestWithZeroConfidenceAfterTwoMinutes() = runTest {
        val f = Fx(this); f.reg("watch_livefit"); f.on("watch_livefit"); f.start()
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
        val f = Fx(this); f.reg("watch_livefit"); f.on("watch_livefit"); f.start()
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
        val f = Fx(this); f.reg("phone_car"); f.reg("watch_livefit"); f.on("phone_car", "watch_livefit"); f.start()
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        f.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.adv(60_000)
        f.accept(Evidence.WatchMotion(f.clock(), WatchState.WALKING, 0.9f))
        f.off("phone_car")
        assertEquals("watch walking still stands after the rebuild", ContextState.WALKING, f.hub.message.value!!.state)
        assertFalse("ANDROID_AUTO" in f.hub.message.value!!.reasons)
    }

    @Test fun staleGenerationSinkIsDropped() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.on("phone_car"); f.start()
        val sink = f.hub.sinkFor("phone_car")
        sink.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.ts.runCurrent()
        assertEquals(1L, f.stats.snapshot()["aa"])
        f.off("phone_car")
        sink.accept(Evidence.CarLink(f.clock(), CarLinkKind.ANDROID_AUTO, true)); f.ts.runCurrent()
        assertEquals(1L, f.stats.snapshot()["aa"])
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

    @Test fun statsLineIsLoggedEveryTenMinutesWithoutPrivateData() = runTest {
        val f = Fx(this); f.start(); f.adv(601_000)
        assertTrue(f.logs.any { it.startsWith("ctxhub v=1 state=") })
    }

    @Test fun concurrentIntakeWithSettingsTogglesIsExactAndMatchesSerialReplay() = runTest {
        val f = Fx(this); f.reg("phone_car"); f.reg("watch_livefit"); f.on("watch_livefit"); f.start()
        val at = f.clock()
        val threads = (1..8).map {
            Thread { repeat(500) { f.hub.accept(Evidence.WatchMotion(at, WatchState.WALKING, 0.9f)); f.hub.onMotionHint(MotionHint(MotionState.WALKING, at)) } }
        }
        threads.forEach { it.start() }
        repeat(100) { f.settings.setEnabled("phone_car", it % 2 == 0); Thread.yield() }
        threads.forEach { it.join() }
        f.settings.setEnabled("phone_car", false)
        f.ts.runCurrent(); f.adv(10_000)
        assertEquals(4000L, f.stats.snapshot()["watch"])
        assertEquals(4000L, f.stats.snapshot()["hints"])
        val ref = ContextFusion(FusionParams(watchTtlMs = 1_500_000))
        repeat(4000) { ref.onEvidence(Evidence.WatchMotion(at, WatchState.WALKING, 0.9f)); ref.onEvidence(Evidence.GlassesMotion(at, MotionState.WALKING, -1f)) }
        assertEquals(ref.evaluate(f.clock()).state, f.hub.decision.value.state)
        assertNotNull(f.hub.message.value)
    }
}
