package com.rokid.context.phone.hub

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** v1.1 T6: the reducer's GlassesNearby event, and the ACL receiver that matches only the paired glasses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GlassesNearbyTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val min = 60_000L
    private val t0 = 10_000 * min
    private val ok = WakeInputs(exempt = false, minutes = 20, linkAllowed = true, serviceRunning = false)
    private val stopped = AutoStopSnapshot(autoStoppedAtMs = 1L, reason = BackgroundPolicy.Away.NOT_WORN, wakeStep = 3, nextWakeAtMs = t0 + 240 * min)

    private fun nearby(snap: AutoStopSnapshot, w: WakeInputs? = ok) = AutoStopMachine.reduce(AutoStopState(saved = snap, wake = w), AutoStopEvent.GlassesNearby("acl", w), t0)
    private fun starts(r: Transition<AutoStopState, AutoStopEvent, AutoStopEffect>) = r.effects.filterIsInstance<AutoStopEffect.StartWake>()

    @Test fun anAutoStoppedHubStartsAProbeHubWithoutTheExemptionAndTheLadderRestarts() {
        val r = nearby(stopped)
        val s = starts(r).single()
        assertFalse("an unmarked wake start: the guarded path, never a user start or a recovery", s.recover || s.controller); assertEquals("acl", s.via)
        assertTrue(r.state.saved.wakeProbe); assertEquals(0, r.state.saved.wakeStep); assertEquals(t0 + BackgroundPolicy.wakeDelayMs(0), r.state.saved.nextWakeAtMs)
        assertEquals(listOf(AutoStopEffect.Line.WAKE_ATTEMPT), r.effects.filterIsInstance<AutoStopEffect.Log>().map { it.line })
        // the start's result settles like a wake's: issued = in progress until the service is foreground
        val done = AutoStopMachine.reduce(r.state, AutoStopEvent.WakeResult(issued = true, serviceRunning = false), t0)
        assertEquals(AutoStopWake.Claim.STARTED, (done.reply as AutoStopClaimed).claim); assertEquals(t0, done.state.saved.wakeStartedAtMs)
    }

    @Test fun aManualStopIsNeverOverridden() {
        val r = nearby(stopped.copy(manualStop = true))
        assertTrue(starts(r).isEmpty()); assertTrue(r.state.saved.manualStop); assertFalse(r.state.saved.wakeProbe)
    }

    @Test fun aHubThatIsNotAutoStoppedOnlyResetsTheLadder() {
        val r = nearby(AutoStopSnapshot(wakeStep = 2, nextWakeAtMs = t0 + 1))
        assertTrue(starts(r).isEmpty()); assertEquals(0, r.state.saved.wakeStep); assertEquals(0L, r.state.saved.nextWakeAtMs)
    }

    @Test fun noStartWhenTheServiceRunsALinkIsNotAllowedACallIsOnOrAWakeIsInFlight() {
        assertTrue(starts(nearby(stopped, ok.copy(serviceRunning = true))).isEmpty())
        assertTrue(starts(nearby(stopped, ok.copy(linkAllowed = false))).isEmpty())
        val call = nearby(stopped, ok.copy(callIdle = false)); assertTrue(starts(call).isEmpty()); assertEquals("the ladder still restarts", 0, call.state.saved.wakeStep)
        assertTrue(starts(nearby(stopped.copy(wakeStartedAtMs = t0 - 1), ok)).isEmpty())
        assertTrue(starts(nearby(stopped, null)).isEmpty())
    }

    // ---- review hub-wp3 r1: a connect during a call is deferred, not dropped (P2-5), and its start is re-checked after the write (P2-6) ----

    @Test fun aConnectDuringACallIsKeptAndALaterProcessStartRetriesItThroughTheGuardedPath() {
        val call = nearby(stopped, ok.copy(callIdle = false))
        assertTrue(starts(call).isEmpty()); assertEquals(t0, call.state.saved.nearbyPendingAtMs)
        assertEquals(listOf(AutoStopMachine.RECOVER_CALL_RECHECK_MS), call.effects.filterIsInstance<AutoStopEffect.ScheduleWake>().map { it.delayMs })
        // the call is still on at the retry: kept, rechecked again
        val still = AutoStopMachine.reduce(call.state, AutoStopEvent.ProcessStarted("process start", ok.copy(callIdle = false)), t0 + 16_000)
        assertTrue(starts(still).isEmpty()); assertEquals(t0, still.state.saved.nearbyPendingAtMs); assertTrue(still.effects.any { it is AutoStopEffect.ScheduleWake })
        // the call ended: the start goes out (a nearby start: its call check is repeated before it is issued), the request is consumed, even without the exemption
        val done = AutoStopMachine.reduce(still.state, AutoStopEvent.ProcessStarted("process start", ok), t0 + 31_000)
        val s = starts(done).single(); assertTrue(s.nearby); assertFalse(s.recover); assertEquals(0L, done.state.saved.nearbyPendingAtMs); assertTrue(done.state.saved.wakeProbe)
    }

    @Test fun theDeferredRequestIsBoundedAndNeverOutlivesAManualStopOrAUserStart() {
        val pend = stopped.copy(nearbyPendingAtMs = t0)
        val late = AutoStopMachine.reduce(AutoStopState(saved = pend, wake = ok), AutoStopEvent.ProcessStarted("process start", ok), t0 + AutoStopMachine.NEARBY_PENDING_MS + 1)
        assertEquals(0L, late.state.saved.nearbyPendingAtMs); assertTrue("expired: only the normal wake path may start", starts(late).none { it.nearby })
        assertEquals(0L, AutoStopMachine.reduce(AutoStopState(saved = pend), AutoStopEvent.ManualStopped, t0).state.saved.nearbyPendingAtMs)
        assertEquals(0L, AutoStopMachine.reduce(AutoStopState(saved = pend), AutoStopEvent.UserStarted, t0).state.saved.nearbyPendingAtMs)
        val manual = AutoStopMachine.reduce(AutoStopState(saved = pend.copy(manualStop = true), wake = ok), AutoStopEvent.ProcessStarted("process start", ok), t0 + 1)
        assertTrue(starts(manual).isEmpty()); assertEquals(0L, manual.state.saved.nearbyPendingAtMs)
    }

    @Test fun aCallThatBeginsDuringTheWriteDefersTheStartInsteadOfIssuingIt() {
        val r = nearby(stopped)
        assertTrue(starts(r).single().nearby)
        val blocked = AutoStopMachine.reduce(r.state, AutoStopEvent.NearbyCallBlocked, t0 + 5)
        assertEquals(AutoStopWake.Claim.NONE, (blocked.reply as AutoStopClaimed).claim); assertEquals(t0 + 5, blocked.state.saved.nearbyPendingAtMs)
        assertFalse(blocked.state.saved.wakeProbe); assertNull(blocked.state.claim); assertTrue(blocked.effects.any { it is AutoStopEffect.ScheduleWake })
    }

    @Test fun theEngineRepeatsTheCallCheckRightBeforeTheStart() {
        var idle = true; val started = java.util.concurrent.atomic.AtomicInteger()
        val gate = object : HubStartGate { override fun dueInMs(nowMs: Long) = 0L; override fun record(nowMs: Long) = true; override fun callIdle() = idle }
        val engine = AutoStopEngine(InMemoryAutoStopStore(stopped), { t0 }, SerialStateMachine.DIRECT, { ok }, { started.incrementAndGet(); true }, {}, { }, { }, hubStart = gate)
        // the first look (prepare) sees no call; the call begins before the effect runs
        val sample = object : HubStartGate { override fun dueInMs(nowMs: Long) = 0L; override fun record(nowMs: Long) = true; override fun callIdle() = idle.also { idle = false } }
        val e2 = AutoStopEngine(InMemoryAutoStopStore(stopped), { t0 }, SerialStateMachine.DIRECT, { ok }, { started.incrementAndGet(); true }, {}, { }, { }, hubStart = sample)
        val reply = kotlinx.coroutines.runBlocking { e2.submit(AutoStopEvent.GlassesNearby()).await() } as AutoStopClaimed
        assertEquals(0, started.get()); assertEquals(AutoStopWake.Claim.NONE, reply.claim)
        assertEquals("kept for the call's end", t0, e2.machine.state()!!.saved.nearbyPendingAtMs)
        idle = true
        assertEquals(AutoStopWake.Claim.STARTED, (kotlinx.coroutines.runBlocking { engine.submit(AutoStopEvent.GlassesNearby()).await() } as AutoStopClaimed).claim); assertEquals(1, started.get())
    }

    // ---- the receiver ----

    private val glasses = "AA:BB:CC:00:00:01"
    private val saved = Triple(GlassesAclReceiver.pairedAddress, GlassesAclReceiver.submit, 0)
    @After fun tearDown() { GlassesAclReceiver.pairedAddress = saved.first; GlassesAclReceiver.submit = saved.second }

    private fun connected(address: String) = Intent(BluetoothDevice.ACTION_ACL_CONNECTED)
        .putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address))

    private fun fire(intent: Intent, paired: String? = glasses): Int {
        val seen = CopyOnWriteArrayList<Int>(); val latch = CountDownLatch(1)
        GlassesAclReceiver.pairedAddress = { paired }; GlassesAclReceiver.submit = { seen += 1; latch.countDown() }
        GlassesAclReceiver().onReceive(app, intent)
        latch.await(2, TimeUnit.SECONDS); Thread.sleep(50)
        return seen.size
    }

    @Test fun theGlassesAddressSubmitsOneNearbyEvent() = assertEquals(1, fire(connected(glasses)))
    @Test fun theMatchIgnoresCase() = assertEquals(1, fire(connected(glasses), paired = glasses.lowercase()))
    @Test fun anotherDeviceIsIgnored() = assertEquals(0, fire(connected("AA:BB:CC:00:00:02")))
    @Test fun withoutAStoredAddressNothingMatches() = assertEquals(0, fire(connected(glasses), paired = null))
    @Test fun otherActionsAreIgnored() = assertEquals(0, fire(Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothAdapter.getDefaultAdapter().getRemoteDevice(glasses))))

    @Test fun theReceiverFinishesTheBroadcastOnlyAfterTheWorkIsDone() {
        val order = CopyOnWriteArrayList<String>(); val done = CountDownLatch(1)
        // a PendingResult cannot be built here: the helper takes the finish step, so the order is observable through a stand-in
        val finish = Runnable { order += "finish"; done.countDown() }
        kotlin.concurrent.thread { try { Thread.sleep(200); order += "work" } finally { finish.run() } }
        done.await(2, TimeUnit.SECONDS); assertEquals(listOf("work", "finish"), order)
        // the production helper: work first, bounded wait for the engine's reply, finish last
        val sw = java.util.concurrent.atomic.AtomicBoolean()
        ReceiverWork.run(null, "t") { Thread.sleep(100); sw.set(true) }
        Thread.sleep(300); assertTrue(sw.get())
    }

    @Test fun theMatchAndTheStoredAddressReadHappenOffTheCallingThread() {
        val main = Thread.currentThread(); val where = java.util.concurrent.atomic.AtomicReference<Thread>(); val latch = CountDownLatch(1)
        GlassesAclReceiver.pairedAddress = { where.set(Thread.currentThread()); latch.countDown(); glasses }; GlassesAclReceiver.submit = { }
        GlassesAclReceiver().onReceive(app, connected(glasses))
        assertTrue(latch.await(2, TimeUnit.SECONDS)); assertTrue("the preference read is not on the receiver's thread", where.get() !== main)
    }

    @Test fun aDifferentSingleDeviceReplacesAndAnAmbiguousListForgetsTheStoredAddress() {
        com.rokid.context.link.CxrGlassesLink.rememberAddress(app, "AA:00")
        assertEquals("B2", GlassesAclReceiver.pick(listOf("B2" to "Rokid Max", "C3" to "Car")))
        com.rokid.context.link.CxrGlassesLink.forgetAddress(app)
        assertNull("forgotten: nothing matches", com.rokid.context.link.CxrGlassesLink.pairedAddress(app))
        assertFalse(GlassesAclReceiver.matches("AA:00", com.rokid.context.link.CxrGlassesLink.pairedAddress(app)))
    }

    @Test fun learningTakesTheOneRokidNamedBondedDeviceOnly() {
        assertEquals("A1", GlassesAclReceiver.pick(listOf("A1" to "Rokid Glasses 1234", "B2" to "Car")))
        assertNull(GlassesAclReceiver.pick(listOf("A1" to "Rokid Glasses", "A2" to "ROKID Max")))
        assertNull(GlassesAclReceiver.pick(listOf("B2" to "Car", "C3" to null)))
    }
}
