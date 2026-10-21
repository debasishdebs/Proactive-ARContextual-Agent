package com.rokid.context.phone.context

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.IntentOutput
import com.rokid.context.core.model.context.CarLinkKind
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextReason
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.IntentLabel
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.intent.IntentFrame
import com.rokid.context.work.frames.intent.IntentFrameStore
import com.rokid.context.work.frames.intent.IntentSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ImageIntentSourceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val settings = ContextSourceSettings(ctx.getSharedPreferences("ii_${n.incrementAndGet()}", 0).also { it.edit().clear().commit() })
    private companion object { val n = AtomicInteger() }

    @After fun tearDown() { KeyframeGraph.intentSampler = null }

    private fun dec(state: ContextState, vararg reasons: ContextReason) =
        ContextDecision(state, 0.9f, 0L, reasons.toSet(), null, state, false)

    private class Slot { var sampler: IntentSampler? = null }

    private class Captured {
        var episode: (() -> Boolean)? = null
        var onResult: ((String, Long, IntentOutput) -> Unit)? = null
        var stats: ((Boolean?) -> Unit)? = null
        var sampler: IntentSampler? = null
        var creates = 0
    }

    private fun newSource(scope: CoroutineScope, sink: (Evidence) -> Unit, cap: Captured, slot: Slot, d: () -> ContextDecision = { dec(ContextState.UNKNOWN) }, stats: ContextHubStats = ContextHubStats { 0L }) =
        ImageIntentSource(
            sinkFor = { EvidenceSink { sink(it) } }, decision = d, stats = stats, enabled = { true },
            create = { ep, res, st, en ->
                cap.creates++; cap.episode = ep; cap.onResult = res; cap.stats = st
                IntentSampler(scope, object : IntentFrameStore {
                    override suspend fun newestUnlabelled(sinceReceivedMs: Long): IntentFrame? = null
                    override suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long) = 1
                    override suspend fun tombstoned(frameId: String) = false
                }, { null }, { 0L }, ep, res, en, st).also { cap.sampler = it }
            },
            install = { slot.sampler = it }, installed = { slot.sampler },
        )

    @Test fun idAvailabilityAndDefaultOff() {
        val s = newSource(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), {}, Captured(), Slot())
        assertEquals("image_intent", s.id); assertTrue(s.available())
        assertFalse("opt-in: off until the user turns it on", settings.enabled("image_intent"))
    }

    @Test fun startInstallsTheSamplerAndStopRemovesAndClosesIt() {
        val cap = Captured(); val slot = Slot()
        val s = newSource(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), {}, cap, slot)
        assertNull(slot.sampler)
        assertTrue(s.start())
        assertNotNull(slot.sampler); assertSame(cap.sampler, slot.sampler)
        s.stop()
        assertNull(slot.sampler)
    }

    @Test fun stopLeavesAnotherSourceRunsSamplerAlone() {
        val cap = Captured(); val slot = Slot()
        val s = newSource(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), {}, cap, slot)
        s.start()
        val other = cap.sampler; slot.sampler = null            // someone else owns the slot now
        s.stop()
        assertNull(slot.sampler); assertNotNull(other)
    }

    @Test fun aResultIsFedAsEvidenceStampedWithTheCaptureTimeAndRecordedInTheStats() {
        val cap = Captured(); val slot = Slot(); val fed = ArrayList<Evidence>(); val st = ContextHubStats { 0L }
        val s = newSource(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { fed += it }, cap, slot, stats = st)
        s.start()
        cap.onResult!!("f", 12_345L, IntentOutput(IntentLabel.DRIVING, 0.9f))
        assertEquals(listOf<Evidence>(Evidence.Intent(12_345L, IntentLabel.DRIVING, 0.9f)), fed)
        assertEquals(1, s.events)
        assertTrue(st.line(ContextState.UNKNOWN).contains("intentLast=DRIVING@90"))
    }

    @Test fun samplerStatsFeedTheHubCounters() {
        val cap = Captured(); val slot = Slot(); val st = ContextHubStats { 0L }
        newSource(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), {}, cap, slot, stats = st).start()
        cap.stats!!(true); cap.stats!!(false); cap.stats!!(null); cap.stats!!(null)
        val m = st.snapshot()
        assertEquals(1L, m["intent"]); assertEquals(1L, m["intentFail"]); assertEquals(2L, m["intentSkip"])
    }

    @Test fun anEpisodeIsAVehicleStateStartedByAListedBluetoothDeviceOnly() {
        val ep = ImageIntentSource.Companion::episode
        assertTrue(ep(dec(ContextState.VEHICLE_DRIVING, ContextReason.MY_CAR_BT)))
        assertTrue(ep(dec(ContextState.VEHICLE_PASSENGER, ContextReason.MY_CAR_BT, ContextReason.IMAGE_PASSENGER)))
        assertFalse("a watch or activity ride is not a listed-device episode", ep(dec(ContextState.VEHICLE_PASSENGER, ContextReason.WATCH_VEHICLE, ContextReason.AR_VEHICLE)))
        assertFalse("an unlisted car-class BT device is not either", ep(dec(ContextState.VEHICLE_DRIVING, ContextReason.CAR_BT)))
        assertFalse("images alone are never an episode", ep(dec(ContextState.VEHICLE_PASSENGER, ContextReason.IMAGE_VEHICLE)))
        assertFalse(ep(dec(ContextState.UNKNOWN, ContextReason.MY_CAR_BT)))
        assertFalse(ep(dec(ContextState.REST)))
    }

    @Test fun theSamplersEpisodeFollowsTheHubDecision() {
        val cap = Captured(); var d = dec(ContextState.UNKNOWN)
        newSource(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), {}, cap, Slot(), d = { d }).start()
        assertFalse(cap.episode!!())
        d = dec(ContextState.VEHICLE_DRIVING, ContextReason.MY_CAR_BT)
        assertTrue(cap.episode!!())
    }

    // ---- through the real hub and the real fusion
    private class FakeCar(override val id: String = "phone_car", val hub: () -> ContextHub) : ContextSource {
        var sink: EvidenceSink? = null
        override fun available() = true
        override fun start(): Boolean { sink = hub().sinkFor(id); return true }
        override fun stop() {}
        override val events = 0
    }

    @Test fun intentEvidenceThroughTheHubNeverStartsAVehicleAndOffMeansNotRunning() = runTest {
        val t0 = 1_793_000_000_000L
        val clock: () -> Long = { t0 + testScheduler.currentTime }
        val reg = ContextSourceRegistry(); val cap = Captured(); var hubRef: ContextHub? = null
        val hub = ContextHub(backgroundScope, settings, reg, clock = clock).also { hubRef = it }
        val src = ImageIntentSource(
            sinkFor = hub::sinkFor, decision = { hub.decision.value }, stats = hub.stats, enabled = { settings.enabled("image_intent") },
            create = { ep, res, st, en -> cap.onResult = res; IntentSampler(backgroundScope, object : IntentFrameStore {
                override suspend fun newestUnlabelled(sinceReceivedMs: Long): IntentFrame? = null
                override suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long) = 1
                override suspend fun tombstoned(frameId: String) = false
            }, { null }, clock, ep, res, en, st) },
            install = {}, installed = { null },
        )
        reg.register(src)
        hub.start(); runCurrent()
        assertEquals("opt-in switch defaults to off", SourceStatus.OFF, hub.status.value["image_intent"])
        settings.setEnabled("image_intent", true); runCurrent()
        assertEquals(SourceStatus.ON, hub.status.value["image_intent"])
        // two confident DRIVING / PASSENGER images, then a long wait: still UNKNOWN, still no suspicion
        cap.onResult!!("a", clock(), IntentOutput(IntentLabel.VEHICLE_PASSENGER, 0.95f)); runCurrent()
        testScheduler.advanceTimeBy(10_000); cap.onResult!!("b", clock(), IntentOutput(IntentLabel.VEHICLE_PASSENGER, 0.95f)); runCurrent()
        testScheduler.advanceTimeBy(5 * 60_000); runCurrent()
        assertEquals(ContextState.UNKNOWN, hub.decision.value.state); assertFalse(hub.decision.value.vehicleSuspected)
        settings.setEnabled("image_intent", false); runCurrent()
        assertEquals(SourceStatus.OFF, hub.status.value["image_intent"])
    }
}
