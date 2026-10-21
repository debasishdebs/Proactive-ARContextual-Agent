package com.rokid.context.phone.hub

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PERM (R12, S12): the call-end settle is measured from the transition into NORMAL, on the monotonic clock, driven through the guard. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AndroidRelaunchGuardsTest {
    private var mode = AudioManager.MODE_NORMAL
    private var e = 100_000L
    private fun guards() = AndroidRelaunchGuards(
        ApplicationProvider.getApplicationContext(), { null }, clock = { 0L }, elapsed = { e },
        modeSource = { mode }, callStateSource = { null }, watchModeChanges = false,
    )

    @Test fun hangUpJustBeforePollStillSettles15s() {
        val g = guards()
        mode = AudioManager.MODE_IN_CALL; assertFalse(g.callIdle())
        e = 200_000L; mode = AudioManager.MODE_NORMAL // the call ended here; no poll saw it yet
        e = 201_000L; assertFalse("a poll 1 s later sees NORMAL after a call", g.callIdle())
        e = 214_900L; assertFalse(g.callIdle())
        e = 215_900L; assertFalse("the transition is stamped no earlier than the poll that saw it", g.callIdle())
        e = 216_000L; assertTrue(g.callIdle())
    }

    @Test fun listenerTransitionStampsNormalSince() {
        val g = guards()
        e = 100_000L; g.onModeChanged(AudioManager.MODE_IN_CALL)
        e = 200_000L; g.onModeChanged(AudioManager.MODE_NORMAL); mode = AudioManager.MODE_NORMAL
        e = 214_999L; assertFalse(g.callIdle())
        e = 215_000L; assertTrue(g.callIdle())
    }

    @Test fun neverInACallIsIdleAtOnce() {
        val g = guards()
        assertTrue(g.callIdle())
    }

    @Test fun anotherNonNormalModeRestartsTheSettle() {
        val g = guards()
        mode = AudioManager.MODE_RINGTONE; assertFalse(g.callIdle())
        e = 120_000L; mode = AudioManager.MODE_NORMAL; assertFalse(g.callIdle())
        e = 130_000L; mode = AudioManager.MODE_IN_COMMUNICATION; assertFalse(g.callIdle())
        e = 140_000L; mode = AudioManager.MODE_NORMAL; assertFalse(g.callIdle())
        e = 154_999L; assertFalse(g.callIdle())
        e = 155_000L; assertTrue(g.callIdle())
    }
}
