package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Review hub-wp2 r1: the production prefs store and the production call guard (real 15 s settle on a fake clock) under the recovery connect. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GlassesRelauncherRecoveryTest {
    /** The production store: the sixth attempt is committed (not apply()-ed) before the launch, so a rebuilt store allows no seventh. */
        @Test fun theAttemptsAreCommittedAndAFreshStoreSeesThem() {
            val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            val prefs = ctx.getSharedPreferences("relaunch_state_durable_test", 0).also { it.edit().clear().commit() }
            assertTrue(PrefsRelaunchStore(prefs).saveAttemptsDurable(listOf(1L, 2L, 3L, 4L, 5L, 6L)))
            // a new process: another SharedPreferences instance over the same file sees all six
            val again = PrefsRelaunchStore(ctx.getSharedPreferences("relaunch_state_durable_test", 0))
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), again.attempts())
            assertEquals("a rebuilt relauncher holds the cap", 6, PrefsRelaunchStore(ctx.getSharedPreferences("relaunch_state_durable_test", 0)).attempts().size)
            prefs.edit().clear().commit()
        }

    /** A real 15 s settle (the production guard on a fake clock): a hang-up is not idle until 15 s later, so a recovery connect waits exactly that long. */
    @Test fun aRecoveryConnectWaitsTheRealFifteenSecondSettleAfterACall() = runTest {
        var mode = android.media.AudioManager.MODE_IN_CALL
        val guards = AndroidRelaunchGuards(androidx.test.core.app.ApplicationProvider.getApplicationContext(), { null }, clock = { currentTime + 1_000_000L }, elapsed = { currentTime }, modeSource = { mode }, callStateSource = { null }, watchModeChanges = false)
        val state = MutableStateFlow(LinkState.OFFLINE); var launches = 0
        val r = GlassesRelauncher(backgroundScope, state, { true }, guards, { launches++ }, { currentTime }, {}, io = kotlinx.coroutines.Dispatchers.Unconfined)
        r.start(); runCurrent(); r.connectAfterRecovery(); runCurrent()
        advanceTimeBy(20_000); assertEquals("in a call", 0, launches)
        mode = android.media.AudioManager.MODE_NORMAL; // the call ends now; the next poll stamps it
        advanceTimeBy(10_000); assertEquals("under 15 s since the end: still settling", 0, launches)
        advanceTimeBy(10_100); assertEquals("past 15 s: connects", 1, launches)
    }
}
