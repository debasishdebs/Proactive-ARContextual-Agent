package com.rokid.context.phone.hub

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** v1.1 T4: boot and update only submit the process-start event (the Recover branch decides); every other action is ignored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class HubBootReceiverTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val saved = HubBootReceiver.submit
    @After fun tearDown() { HubBootReceiver.submit = saved }

    private fun fire(action: String?): List<String> {
        val seen = CopyOnWriteArrayList<String>(); val latch = CountDownLatch(1)
        HubBootReceiver.submit = { _, via -> seen += via; latch.countDown() }
        HubBootReceiver().onReceive(app, Intent(action))
        latch.await(if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) 5 else 0, TimeUnit.SECONDS)
        Thread.sleep(50)
        return seen
    }

    @Test fun bootSubmitsProcessStartedBoot() = assertEquals(listOf("boot"), fire(Intent.ACTION_BOOT_COMPLETED))
    @Test fun updateSubmitsProcessStartedUpdate() = assertEquals(listOf("update"), fire(Intent.ACTION_MY_PACKAGE_REPLACED))
    @Test fun anyOtherActionIsIgnored() { assertTrue(fire("android.intent.action.SOMETHING").isEmpty()); assertTrue(fire(null).isEmpty()) }

    @Test fun aLostHubIsRecoveredThroughTheExistingBranchWithTheBootReason() {
        val snap = AutoStopSnapshot(hubLive = true)
        val wake = WakeInputs(exempt = true, minutes = 20, linkAllowed = true, serviceRunning = false)
        val t = AutoStopMachine.reduce(AutoStopState(saved = snap), AutoStopEvent.ProcessStarted("boot", wake), 1_000L)
        assertTrue(t.effects.any { it is AutoStopEffect.StartWake && it.recover && it.via == "boot" })
        assertTrue(t.effects.any { it is AutoStopEffect.Log && it.line == AutoStopEffect.Line.HUB_RECOVER_ATTEMPT && it.via.startsWith("boot") })
    }
}
