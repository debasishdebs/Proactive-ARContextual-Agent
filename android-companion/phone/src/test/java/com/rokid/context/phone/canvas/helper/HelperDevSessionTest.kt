package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.PyOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * WP6 RED group (S52, fake clock and fake network): ONE termination operation, a session-owned network observer, and a four-hour inactivity expiry that only user canvas
 * activity extends. Built on the production wiring over in-memory Room; only the clock, the Wi-Fi callback and the server stop are fakes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HelperDevSessionTest {
    private lateinit var h: HelperHarness
    private lateinit var token: String
    private val hours4 = 4 * 3_600_000L

    @Before fun setUp() { h = HelperHarness(ApplicationProvider.getApplicationContext()); token = h.pairHelper() }
    @After fun tearDown() { h.close() }

    private fun browserRequest() { val t = (h.browserAuth.pair(h.browserAuth.newCode().code) as com.rokid.context.phone.canvas.server.CanvasAuth.Pair.Ok).token; h.call("GET", "/api/session", t) }

    private fun assertEnded(jobId: String?, cause: String) {
        assertFalse("grant is off", h.backend.grants.isOn(HelperGrants.PY_EXEC))
        assertFalse("token revoked", h.backend.auth.check(token))
        assertEquals("helper request is 401", 401, h.poll(token).status)
        assertNull("session gone", h.backend.auth.session())
        if (jobId != null) {
            val row = runBlocking { h.dao.helperJob(jobId) }!!
            assertFalse("$cause: job closed (${row.state})", row.state in setOf("AWAITING_CONFIRM", "QUEUED", "LEASED"))
            assertTrue("$cause: cleanup enqueued", runBlocking { h.dao.pendingCleanup(50) }.any { it.jobId == jobId })
        }
        assertTrue("$cause: server stopped", h.serverStops >= 1)
        assertFalse("observer unregistered", h.network.registered)
    }

    private fun pendingJob(): String { h.startRun(); return h.awaitPendingJob() }

    // ---- inactivity ----

    @Test fun helperPollsDoNotExtendUserInactivity() {
        browserRequest()
        val start = h.now
        while (h.now + 600_000 - start < hours4) { h.now += 600_000; assertEquals(200, h.poll(token).status); assertFalse(h.backend.session.checkExpiry()) }
        h.now = start + hours4
        assertEquals(200, h.poll(token).status)       // a helper poll right at the limit...
        assertTrue("...does not keep the session alive", h.backend.session.checkExpiry())
        runBlocking { h.backend.session.terminate(SessionEnd.EXPIRY).join() }
        assertEnded(null, "expiry")
    }

    @Test fun fourHoursWithoutUserCanvasActivityEndsSession() {
        val job = pendingJob()
        h.now += hours4 - 1
        assertFalse(h.backend.session.checkExpiry())
        assertTrue(h.backend.session.active)
        h.now += 1
        assertTrue(h.backend.session.checkExpiry())
        runBlocking { h.backend.session.terminate(SessionEnd.EXPIRY).join() }
        assertEnded(job, "expiry")
        assertFalse(h.backend.session.active)
    }

    @Test fun userCanvasRequestResetsTheClock() {
        h.now += hours4 - 1_000
        browserRequest()                                                   // an authenticated browser request
        h.now += hours4 - 1_000
        assertFalse("the clock restarted at the browser request", h.backend.session.checkExpiry())
        h.now += 1_000
        assertTrue(h.backend.session.checkExpiry())
    }

    @Test fun anOpenCanvasScreenCountsAsUserActivity() {
        h.screenOpen = true
        h.now += hours4 * 2
        assertFalse(h.backend.session.checkExpiry())
        h.screenOpen = false
        assertFalse("the screen's last moment counted, the clock starts now", h.backend.session.checkExpiry())
    }

    // ---- LAN loss ----

    @Test fun lanLossEndsSessionAtOnce() {
        val job = pendingJob()
        assertTrue(h.network.registered)
        h.network.lose()
        runBlocking { h.backend.session.awaitEnd() }
        assertEnded(job, "lan loss")
    }

    @Test fun lanLossWithScreenClosedEndsSession() {
        h.screenOpen = false                                // no CanvasHome instance exists in this test at all
        val job = pendingJob()
        h.network.lose()
        runBlocking { h.backend.session.awaitEnd() }
        assertEnded(job, "lan loss, screen closed")
    }

    @Test fun observerIsRegisteredAtSessionStartAndUnregisteredAfterEnd() {
        assertEquals(1, h.network.registrations)
        runBlocking { h.backend.session.endHelperSession().join() }
        assertEquals(1, h.network.unregistrations)
        assertFalse(h.network.registered)
        // a second session registers again; a second end after it unregisters once more
        h.backend.native.acceptPairing()
        assertEquals(2, h.network.registrations)
    }

    @Test fun observerUnregisteredAfterEnd() {
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        assertFalse(h.network.registered)
        h.network.lose()                                    // a late callback does nothing
        assertEquals(1, h.serverStops)
    }

    // ---- one operation, every cause ----

    @Test fun everySessionEndTurnsGrantOffRevokesTokenVoidsJobsAndStopsTheServer() {
        for (cause in SessionEnd.values()) {
            h.close(); setUp()
            val job = pendingJob()
            runBlocking { h.backend.session.terminate(cause).join() }
            assertEnded(job, cause.name)
        }
    }

    @Test fun grantOffAfterEveryStopPath() {
        // notification Stop, IdlePolicy idle stop, four-hour expiry, LAN loss, developer-mode off, explicit disconnect, service shutdown / restart
        val paths: List<Pair<String, (HelperHarness) -> Unit>> = listOf(
            "notification stop" to { x -> runBlocking { x.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() } },
            "idle stop" to { x -> runBlocking { x.backend.session.terminate(SessionEnd.IDLE_STOP).join() } },
            "expiry" to { x -> x.now += hours4; check(x.backend.session.checkExpiry()); runBlocking { x.backend.session.awaitEnd() } },
            "lan loss" to { x -> x.network.lose(); runBlocking { x.backend.session.awaitEnd() } },
            "developer mode off" to { x -> runBlocking { x.backend.session.terminate(SessionEnd.DEVELOPER_MODE_OFF).join() } },
            "service shutdown" to { x -> runBlocking { x.backend.session.terminate(SessionEnd.SHUTDOWN).join() } },
        )
        for ((name, stop) in paths) {
            h.close(); setUp()
            val job = pendingJob()
            stop(h)
            assertEnded(job, name)
        }
    }

    @Test fun theOrderIsGrantOffThenTokenRevokedThenJobsVoidedThenServerStopped() {
        pendingJob()
        h.order.events.clear()
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        val ev = h.order.events.toList()
        assertTrue(ev.toString(), ev.indexOf("hooks:grants_off") < ev.indexOf("hooks:revoke"))
        assertTrue(ev.toString(), ev.indexOf("hooks:revoke") < ev.indexOf("server:stop"))
        assertTrue(ev.toString(), ev.indexOf("jobs:session_end") < ev.indexOf("server:stop"))
        assertEquals("server:stop", ev.last())
    }

    @Test fun terminateIsIdempotent() {
        pendingJob()
        h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).let { runBlocking { it.join() } }
        val grantWrites = h.grantStore.writes.count { it == "clear" }
        val jobsCalls = h.order.events.count { it.startsWith("jobs:") }
        runBlocking { h.backend.session.terminate(SessionEnd.IDLE_STOP).join() }
        runBlocking { h.backend.session.terminate(SessionEnd.SHUTDOWN).join() }
        assertEquals("the end ran once", jobsCalls, h.order.events.count { it.startsWith("jobs:") })
        assertEquals(grantWrites, h.grantStore.writes.count { it == "clear" })
        assertTrue("stopping the server again is harmless and allowed", h.serverStops >= 1)
    }

    @Test fun anEndThatThrowsStillStopsTheServer() {
        h.grantStore.inner.failWrites = true                     // the grant file refuses: the hook fails closed, jobs are still voided, the server still stops
        val job = pendingJob()
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        assertTrue(h.serverStops >= 1)
        val row = runBlocking { h.dao.helperJob(job) }!!
        assertEquals("VOIDED", row.state)
    }

    @Test fun terminateVoidsPendingJobsAndTheRunFailsVoided() {
        val run = h.startRun()
        h.awaitPendingJob()
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        assertEquals(PyOutcome.Voided, runBlocking { run.await() })
        assertTrue(h.backend.adapter.pending().isEmpty())
    }

    @Test fun recoverAtStartTurnsGrantOffAbortsOpenJobsAndDoesNotStopTheServer() {
        val job = pendingJob()
        runBlocking { h.backend.session.recoverAtStart().join() }
        assertFalse(h.backend.grants.isOn(HelperGrants.PY_EXEC))
        assertFalse(h.backend.auth.check(token))
        assertEquals("ABORTED", runBlocking { h.dao.helperJob(job) }!!.state)
        assertEquals("the restart recovery never stops a server a screen just started", 0, h.serverStops)
    }

    @Test fun sessionStartRotatesTheConfirmKeyOnceAndEveryEndRotatesIt() {
        val b = com.rokid.context.memory.canvas.ConfirmBinding("j", "a".repeat(64), 1, 99_000L)
        val t0 = h.backend.confirm.tag(b)
        h.backend.native.acceptPairing()                        // the session is already active: no second rotation
        h.backend.native.setGrant(HelperGrants.PY_EXEC, true)
        assertEquals(t0, h.backend.confirm.tag(b))
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        val t1 = h.backend.confirm.tag(b)
        assertTrue("the end rotates the key: no tag survives a session", t1 != t0)
        h.backend.native.acceptPairing()
        assertTrue("a new session rotates again", h.backend.confirm.tag(b) != t1)
        assertTrue("an old tag does not verify any more", !h.backend.confirm.verify(b, t0, 0L))
    }

    // ---- review 1 (decision: Disconnect ends the HELPER session only) ----

    @Test fun disconnectEndsOnlyTheHelperSessionAndNeverStopsTheServer() {
        val b = com.rokid.context.memory.canvas.ConfirmBinding("j", "a".repeat(64), 1, 99_000L)
        val t0 = h.backend.confirm.tag(b)
        val job = pendingJob()
        h.order.events.clear()
        h.backend.native.disconnectHelper()
        runBlocking { h.backend.session.awaitEnd() }
        assertFalse(h.backend.grants.isOn(HelperGrants.PY_EXEC))
        assertFalse(h.backend.auth.check(token))
        assertTrue("the confirmation key was rotated", h.backend.confirm.tag(b) != t0)
        assertEquals("VOIDED", runBlocking { h.dao.helperJob(job) }!!.state)
        assertTrue(runBlocking { h.dao.pendingCleanup(50) }.any { it.jobId == job })
        assertTrue(h.backend.adapter.pending().isEmpty())
        assertFalse(h.network.registered)
        assertEquals("the canvas server and service stay up while the user is on the screen", 0, h.serverStops)
        val ev = h.order.events.toList()
        assertTrue(ev.toString(), ev.indexOf("hooks:grants_off") < ev.indexOf("hooks:revoke"))
        assertTrue(ev.toString(), ev.none { it == "server:stop" })
        // idempotent, and a later stop path still stops the server without redoing the job end
        val jobsCalls = h.order.events.count { it.startsWith("jobs:") }
        runBlocking { h.backend.session.endHelperSession().join() }
        assertEquals(jobsCalls, h.order.events.count { it.startsWith("jobs:") })
        runBlocking { h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP).join() }
        assertEquals(1, h.serverStops)
        assertEquals(jobsCalls, h.order.events.count { it.startsWith("jobs:") })
    }

    // ---- direct session, queued scope (nothing runs until the test lets it) ----

    private class Rig {
        var now = 0L
        val stops = java.util.concurrent.atomic.AtomicInteger()
        val ends = java.util.concurrent.atomic.AtomicInteger()
        val net = FakeNetworkWatch()
        val ender = object : HelperJobEnder {
            override suspend fun grantOff() = 0
            override suspend fun endSession(cause: com.rokid.context.memory.canvas.HelperDeletionCause): Int { ends.incrementAndGet(); return 0 }
            override suspend fun recover() = 0
        }
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        val scope = kotlinx.coroutines.CoroutineScope(dispatcher)
        fun session(checkEveryMs: Long = 60_000, scope: kotlinx.coroutines.CoroutineScope = this.scope) =
            HelperDevSession({ now }, scope, ender, com.rokid.context.memory.canvas.HelperSessionHooks.NONE, net, { 0L }, { false }, { stops.incrementAndGet() }, inactivityMs = 1_000, checkEveryMs = checkEveryMs)
    }

    @Test fun aTerminateAfterTheEndStopsTheServerBeforeItReturns() {
        val r = Rig(); val s = r.session()
        s.start(); s.terminate(SessionEnd.NOTIFICATION_STOP)
        r.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, r.stops.get())
        s.terminate(SessionEnd.SHUTDOWN)                               // nothing has been given a chance to run: the stop must already have happened
        assertEquals(2, r.stops.get())
    }

    @Test fun aStartWhileAnEndIsStillQueuedDoesNotOpenASessionTheEndWouldKill() {
        val r = Rig(); val s = r.session()
        s.start(); s.terminate(SessionEnd.NOTIFICATION_STOP)
        s.start()
        assertFalse("no active session with credentials the queued end will revoke", s.active)
        assertEquals(1, r.net.registrations)
        r.dispatcher.scheduler.advanceUntilIdle()
        s.start()
        assertTrue(s.active)
    }

    @Test fun theExpiryWatcherLoopEndsTheSessionByItself() {
        val r = Rig()
        val real = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            val s = r.session(checkEveryMs = 10, scope = real)
            s.start()
            r.now = 1_000
            val end = System.currentTimeMillis() + 5_000
            while (r.ends.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
            assertEquals(1, r.ends.get())
            assertFalse(s.active)
        } finally { real.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun theSettingsDeveloperSwitchUsesTheOneEndPath() {
        val src = java.io.File("src/main/java/com/rokid/context/phone/ui/shell/SettingsDestinations.kt").readText()
        assertTrue(src.contains("terminateSession(") && src.contains("SessionEnd.DEVELOPER_MODE_OFF"))
        assertFalse(src.contains("it.stop(); it.revokeAll()"))
    }
}
