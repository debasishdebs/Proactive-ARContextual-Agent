package com.rokid.context.phone.canvas

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.rokid.context.phone.canvas.helper.SessionEnd

class ThrowingCanvasService : CanvasService() {
    override fun enterForeground(text: String) { throw IllegalStateException("not allowed") }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasServiceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var host: FakeCanvasHost

    @Before fun setUp() {
        DeveloperModeStore.get(app).set(true)
        host = FakeCanvasHost()
        CanvasGraph.installForTests(host)
    }

    @After fun tearDown() {
        CanvasGraph.installForTests(null)
        DeveloperModeStore.get(app).set(false)
    }

    @Test fun onTimeoutStopsServerRevokesAndStopsService() {
        host.start(false)
        val svc = Robolectric.buildService(CanvasService::class.java).create().get()
        svc.onTimeout(1, 1)
        assertTrue(host.calls.contains("stopNotice"))
        assertTrue(host.calls.contains("revoke"))
        assertFalse(host.status.value.on)
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertEquals(CanvasService.NOTICE_TIME_LIMIT, host.status.value.notice)
    }

    @Test fun olderOnTimeoutAlsoStops() {
        host.start(false)
        val svc = Robolectric.buildService(CanvasService::class.java).create().get()
        svc.onTimeout(1)
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertFalse(host.status.value.on)
    }

    @Test fun refusedForegroundStartLeavesServerStoppedWithoutCrash() {
        val ctl = Robolectric.buildService(ThrowingCanvasService::class.java, Intent(app, ThrowingCanvasService::class.java)).create()
        ctl.startCommand(0, 1)
        val svc = ctl.get()
        assertFalse(host.status.value.on)
        assertEquals(CanvasService.NOTICE_NOT_ALLOWED, host.status.value.notice)
        assertTrue(shadowOf(svc).isStoppedBySelf)
        assertFalse(host.calls.contains("start:false"))
    }

    // ---- S52: every way the service stops goes through the ONE termination operation, with its own cause ----

    @Test fun notificationStopTerminatesTheSessionWithThatCause() {
        host.start(false)
        val ctl = Robolectric.buildService(CanvasService::class.java, Intent(app, CanvasService::class.java).setAction(CanvasService.ACTION_STOP)).create()
        ctl.startCommand(0, 1)
        assertEquals(listOf(SessionEnd.NOTIFICATION_STOP), host.terminated)
        assertFalse(host.status.value.on)
    }

    @Test fun developerModeOffTerminatesTheSessionWithThatCause() {
        host.start(false)
        DeveloperModeStore.get(app).set(false)
        val ctl = Robolectric.buildService(CanvasService::class.java, Intent(app, CanvasService::class.java)).create()
        ctl.startCommand(0, 1)
        assertEquals(listOf(SessionEnd.DEVELOPER_MODE_OFF), host.terminated)
    }

    @Test fun theIdleRuleTerminatesTheSessionWithThatCause() {
        host.idle = true
        val svc = Robolectric.buildService(CanvasService::class.java, Intent(app, CanvasService::class.java)).create()
        svc.startCommand(0, 1)
        svc.get().checkStop()
        assertEquals(listOf(SessionEnd.IDLE_STOP), host.terminated)
    }

    @Test fun theTimeLimitTerminatesTheSessionWithThatCause() {
        host.start(false)
        val svc = Robolectric.buildService(CanvasService::class.java).create().get()
        svc.onTimeout(1, 1)
        assertEquals(SessionEnd.TIME_LIMIT, host.terminated.first())
    }

    @Test fun destroyingTheServiceTerminatesTheSessionWithShutdown() {
        host.start(false)
        val ctl = Robolectric.buildService(CanvasService::class.java).create()
        ctl.destroy()
        assertEquals(listOf(SessionEnd.SHUTDOWN), host.terminated)
    }

    @Test fun aRefusedForegroundStartTerminatesTheSession() {
        val ctl = Robolectric.buildService(ThrowingCanvasService::class.java, Intent(app, ThrowingCanvasService::class.java)).create()
        ctl.startCommand(0, 1)
        assertEquals(listOf(SessionEnd.TIME_LIMIT), host.terminated)
    }
}
