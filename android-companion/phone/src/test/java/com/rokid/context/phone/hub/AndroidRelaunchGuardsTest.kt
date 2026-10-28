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

    /** 2026-10-28 diagnostics: the inputs of the last answer, as values and buckets; reading them changes nothing. */
    @Test fun theLastInputsAreKeptAsValuesAndBucketsAndTheAnswerIsUnchanged() {
        val g = guards()
        assertTrue(g.callInputs() == null)
        mode = AudioManager.MODE_IN_CALL; assertFalse(g.callIdle())
        assertTrue(g.callInputs(), g.callInputs() == "audioMode=2 callState=unreadable sinceNormal=<15s sinceScene=>=30m")
        assertTrue(g.callInputs() == g.callInputs())
        assertTrue(AndroidRelaunchGuards.bucket(14_999) == "<15s" && AndroidRelaunchGuards.bucket(15_000) == "<1m" && AndroidRelaunchGuards.bucket(299_999) == "<5m" && AndroidRelaunchGuards.bucket(1_799_999) == "<30m" && AndroidRelaunchGuards.bucket(1_800_000) == ">=30m")
    }

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

    /** User ruling 2026-10-28: a VoIP mode and other non-call modes never block and never restart the settle; a call mode does, and the settle follows its end. */
    @Test fun onlyACallModeBlocksAndRestartsTheSettle() {
        val g = guards()
        mode = AudioManager.MODE_IN_COMMUNICATION; assertTrue("VoIP is not a call", g.callIdle())
        assertTrue("the diagnostics still show the mode", g.callInputs()!!.startsWith("audioMode=3 "))
        mode = AudioManager.MODE_RINGTONE; assertFalse("ringing blocks", g.callIdle())
        e = 120_000L; mode = AudioManager.MODE_IN_CALL; assertFalse(g.callIdle())
        e = 130_000L; mode = AudioManager.MODE_CALL_SCREENING; assertFalse(g.callIdle())
        e = 140_000L; mode = AudioManager.MODE_IN_COMMUNICATION; assertFalse("the call just ended: settling", g.callIdle())
        e = 154_999L; assertFalse(g.callIdle())
        e = 155_000L; assertTrue(g.callIdle())
    }

    @Test fun aReadableNonIdleCallStateBlocksAndSettlesAfterItEnds() {
        var cs: Int? = android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
        val g = AndroidRelaunchGuards(ApplicationProvider.getApplicationContext(), { null }, clock = { 0L }, elapsed = { e }, modeSource = { mode }, callStateSource = { cs }, watchModeChanges = false)
        assertFalse(g.callIdle())
        cs = android.telephony.TelephonyManager.CALL_STATE_IDLE; e = 200_000L; assertFalse("settling after the call", g.callIdle())
        e = 215_000L; assertTrue(g.callIdle())
    }

    /** Review P2: a mode callback (no readable call state) must not start the settle while the last known telephony state is a call. */
    @Test fun aModeCallbackDoesNotStartTheSettleWhileTelephonyWasLastBusy() {
        var cs: Int? = android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
        val g = AndroidRelaunchGuards(ApplicationProvider.getApplicationContext(), { null }, clock = { 0L }, elapsed = { e }, modeSource = { mode }, callStateSource = { cs }, watchModeChanges = false)
        e = 0L; assertFalse(g.callIdle())                                  // busy poll
        e = 1_000L; g.onModeChanged(AudioManager.MODE_NORMAL)              // NORMAL callback, telephony unreadable to it
        cs = android.telephony.TelephonyManager.CALL_STATE_IDLE            // hang-up at 9 s, seen by the poll at 10 s
        e = 10_000L; assertFalse(g.callIdle())
        e = 20_000L; assertFalse("only 10 s after the hang-up was seen", g.callIdle())
        e = 24_900L; assertFalse(g.callIdle())
        e = 25_000L; assertTrue(g.callIdle())
    }
}
