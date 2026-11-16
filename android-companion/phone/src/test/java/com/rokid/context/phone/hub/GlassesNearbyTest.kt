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

    @Test fun learningTakesTheOneRokidNamedBondedDeviceOnly() {
        assertEquals("A1", GlassesAclReceiver.pick(listOf("A1" to "Rokid Glasses 1234", "B2" to "Car")))
        assertNull(GlassesAclReceiver.pick(listOf("A1" to "Rokid Glasses", "A2" to "ROKID Max")))
        assertNull(GlassesAclReceiver.pick(listOf("B2" to "Car", "C3" to null)))
    }
}
