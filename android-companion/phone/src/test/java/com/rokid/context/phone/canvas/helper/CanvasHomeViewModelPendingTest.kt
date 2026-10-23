package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.canvas.CanvasStatus
import com.rokid.context.phone.canvas.FakeCanvasHost
import com.rokid.context.phone.canvas.ui.CanvasHomeViewModel
import com.rokid.context.phone.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * WP6 RED group (c8 #3, fake clock and the REAL service, adapter and session): the home view model's pending-confirmation state is a STARTED-scoped subscription that
 * refreshes every second. It does not depend on `host.status`, drops an entry at expiry, decline, cancel and session end, stops below STARTED, and touches the grant
 * prefs only off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasHomeViewModelPendingTest {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var h: HelperHarness
    private lateinit var host: FakeCanvasHost
    private lateinit var vm: CanvasHomeViewModel

    @Before fun setUp() {
        h = HelperHarness(ApplicationProvider.getApplicationContext())
        h.pairHelper()
        host = FakeCanvasHost(confirm = h.backend.adapter, native = h.backend.native)
        vm = CanvasHomeViewModel(host, ensureService = {}, openUrl = {}, clock = { h.now }, io = main.dispatcher)
    }
    @After fun tearDown() { vm.onStop(); h.close() }

    private fun second() = main.dispatcher.scheduler.advanceTimeBy(1_001)
    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out waiting for $what" }; second(); Thread.sleep(10) }
    }
    private fun ids() = vm.state.value.pending.map { it.row.jobId }

    @Test fun aJobCreatedThroughTheRealServiceAppearsWithoutAnyHostStatusChange() {
        vm.onStart()
        val statusBefore = host.status.value
        assertTrue(ids().isEmpty())
        h.startRun()
        val id = h.awaitPendingJob()
        waitFor("the pending row") { id in ids() }
        assertEquals("host.status never changed", statusBefore, host.status.value)
        assertEquals(120, vm.state.value.pending.single().secondsLeft)
    }

    @Test fun anEntryIsRemovedAtExpiry() {
        vm.onStart()
        h.startRun(); val id = h.awaitPendingJob()
        waitFor("the pending row") { id in ids() }
        h.now += 119_000; vm.tick()
        assertEquals(listOf(id), ids())
        h.now += 1_000; vm.tick()
        assertTrue("dropped at expiry", ids().isEmpty())
        waitFor("the service to expire the job") { runBlocking { h.dao.helperJob(id)!!.state } == "FAILED" }
    }

    @Test fun anEntryIsRemovedOnDeclineAndOnCancel() {
        vm.onStart()
        h.startRun(); val a = h.awaitPendingJob()
        waitFor("row a") { a in ids() }
        vm.declineRun(a)
        waitFor("row a gone after decline") { a !in ids() }
        h.startRun(frameIds = listOf("frame-2")); val b = h.awaitPendingJob()
        waitFor("row b") { b in ids() }
        runBlocking { h.backend.service.cancel(b) }                      // a run cancelled elsewhere (the runner)
        waitFor("row b gone after cancel") { b !in ids() }
    }

    @Test fun anEntryIsRemovedOnSessionTermination() {
        vm.onStart()
        h.startRun(); val id = h.awaitPendingJob()
        waitFor("the pending row") { id in ids() }
        runBlocking { h.backend.session.terminate(SessionEnd.LAN_LOSS).join() }
        waitFor("the row gone after terminate") { ids().isEmpty() }
        assertEquals(0, vm.state.value.pending.size)
    }

    @Test fun collectionStopsBelowStartedAndResumesAtStart() {
        val plain = FakeCanvasHost()
        val v = CanvasHomeViewModel(plain, ensureService = {}, openUrl = {}, clock = { 0L }, io = main.dispatcher)
        v.onStart()
        val atStart = plain.fakeConfirm.refreshes
        assertTrue(atStart >= 1)
        main.dispatcher.scheduler.advanceTimeBy(3_001)
        assertTrue("a refresh per second while STARTED", plain.fakeConfirm.refreshes >= atStart + 3)
        v.onStop()
        val atStop = plain.fakeConfirm.refreshes
        main.dispatcher.scheduler.advanceTimeBy(10_000)
        assertEquals("no refresh below STARTED", atStop, plain.fakeConfirm.refreshes)
        v.onStart()
        assertTrue(plain.fakeConfirm.refreshes > atStop)
        v.onStop()
    }

    @Test fun theHelperCardAndTheGrantAreReadAndWrittenOffTheMainThread() {
        val ioExec = Executors.newSingleThreadExecutor { r -> Thread(r, "io-test") }
        try {
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val native = object : com.rokid.context.phone.canvas.helper.HelperNativeActions {
                private fun rec(n: String) { seen += n + "@" + Thread.currentThread().name }
                override fun state(): HelperCardState { rec("state"); return HelperCardState(available = true) }
                override fun acceptPairing() = rec("accept")
                override fun setGrant(grant: String, on: Boolean) = rec("grant")
                override fun disconnectHelper() = rec("disconnect")
            }
            val fake = FakeCanvasHost(native = native)
            val v = CanvasHomeViewModel(fake, ensureService = {}, openUrl = {}, clock = { 0L }, io = ioExec.asCoroutineDispatcher())
            v.onStart(); v.acceptPairing(); v.setPyExecGrant(true); v.disconnectHelper()
            val end = System.currentTimeMillis() + 5_000
            while (seen.size < 4 && System.currentTimeMillis() < end) Thread.sleep(10)
            v.onStop()
            assertTrue(seen.toString(), seen.isNotEmpty() && seen.all { it.contains("@io-test") })
            assertTrue(seen.toString(), seen.map { it.substringBefore('@') }.containsAll(listOf("state", "accept", "grant", "disconnect")))
        } finally { ioExec.shutdownNow() }
    }

    @Test fun tickNoLongerReadsTheHelperSynchronously() {
        val fake = FakeCanvasHost()
        val v = CanvasHomeViewModel(fake, ensureService = {}, openUrl = {}, clock = { 0L }, io = main.dispatcher)
        v.tick()
        assertFalse("tick must not read prefs on the main thread", fake.fakeNative.calls.isNotEmpty() || fake.fakeConfirm.refreshes > 0)
    }

    @Test fun statusIsNotTheOnlySubscription() {
        val s = CanvasStatus(on = true, port = 8765)
        host.status.value = s
        vm.onStart()
        h.startRun(); val id = h.awaitPendingJob()
        waitFor("the pending row") { id in ids() }
        assertEquals(s, host.status.value)
    }
}
