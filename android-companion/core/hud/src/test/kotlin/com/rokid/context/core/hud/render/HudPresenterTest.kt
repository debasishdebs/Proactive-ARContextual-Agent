package com.rokid.context.core.hud.render

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudPresenterTest {
    private fun frame(o: Overlay?) = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok, overlay = o)
    private val nudge = Overlay.Nudge("Leave in 10 min")
    private val confirm = Overlay.Confirm("p1", "Add milk?", "Shopping list")

    @Test fun silentShowsNoPageAndNoNudge() {
        val v = HudPresenter().present(frame(nudge), Verbosity.SILENT, HudNav(page = HudPage.TODAY), local = null, nowMs = 0)
        assertEquals(HudView(page = null, overlay = null), v)
    }

    /** Spec §3.4: user-initiated flows still show in Silent. */
    @Test fun silentKeepsUserFlows() {
        for (o in listOf(Overlay.Listening("wh"), Overlay.Answer("q", "a", speaking = false), confirm, Overlay.PausedSplash)) {
            assertEquals(o, HudPresenter().present(frame(o), Verbosity.SILENT, HudNav(), null, 0).overlay)
        }
    }

    @Test fun mediumShowsANudgeOnlyAsTheOneLiner() {
        val v = HudPresenter().present(frame(nudge), Verbosity.MEDIUM, HudNav(), null, 0)
        assertEquals(HudView(HudPage.GLANCE, null), v)
    }

    @Test fun maxShowsANudgeToastForEightSeconds() {
        val p = HudPresenter()
        assertEquals(nudge, p.present(frame(nudge), Verbosity.MAX, HudNav(), null, 1_000).overlay)
        assertEquals(nudge, p.present(frame(nudge), Verbosity.MAX, HudNav(), null, 8_999).overlay)
        assertNull(p.present(frame(nudge), Verbosity.MAX, HudNav(), null, 9_000).overlay)
    }

    /** Spec §3.2/§5.3: the Confirm overlay hides after 15 s and never votes. */
    @Test fun confirmHidesAfterFifteenSeconds() {
        val p = HudPresenter()
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 0).overlay)
        assertNull(p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 15_000).overlay)
    }

    @Test fun pausedSplashLastsThreeSecondsLocallyOrFromThePhone() {
        val p = HudPresenter()
        assertEquals(Overlay.PausedSplash, p.present(frame(null), Verbosity.MEDIUM, HudNav(), LocalOverlay.Splash(atMs = 0), 2_999).overlay)
        assertNull(p.present(frame(null), Verbosity.MEDIUM, HudNav(), LocalOverlay.Splash(atMs = 0), 3_000).overlay)
        val q = HudPresenter()
        q.present(frame(Overlay.PausedSplash), Verbosity.MEDIUM, HudNav(), null, 0)
        assertNull(q.present(frame(Overlay.PausedSplash), Verbosity.MEDIUM, HudNav(), null, 3_000).overlay)
    }

    @Test fun aLocalToastBeatsAPhoneToastButNotAConfirm() {
        val local = LocalOverlay.Toast("Phone not connected", atMs = 0)
        assertEquals(Overlay.Toast("Phone not connected"), HudPresenter().present(frame(Overlay.Toast("Saved")), Verbosity.MEDIUM, HudNav(), local, 1).overlay)
        assertEquals(confirm, HudPresenter().present(frame(confirm), Verbosity.MEDIUM, HudNav(), local, 1).overlay)
    }

    @Test fun aDismissedAnswerStaysHiddenUntilANewOne() {
        val p = HudPresenter()
        val a = Overlay.Answer("q", "On the shelf", speaking = true)
        p.dismiss(a)
        assertNull(p.present(frame(a), Verbosity.MEDIUM, HudNav(), null, 0).overlay)
        val b = Overlay.Answer("q2", "Level 2", speaking = true)
        assertEquals(b, p.present(frame(b), Verbosity.MEDIUM, HudNav(), null, 1).overlay)
    }

    @Test fun statusPageOnlyInMax() {
        assertEquals(HudPage.GLANCE, HudPresenter().present(frame(null), Verbosity.MEDIUM, HudNav(page = HudPage.STATUS), null, 0).page)
        assertEquals(HudPage.STATUS, HudPresenter().present(frame(null), Verbosity.MAX, HudNav(page = HudPage.STATUS), null, 0).page)
    }

    /** Recording shows Listening at once (before the phone's first partial); the phone's partials replace it. */
    @Test fun recordingShowsListeningUntilThePhoneSendsPartials() {
        val p = HudPresenter()
        assertEquals(Overlay.Listening(""), p.present(frame(null), Verbosity.SILENT, HudNav(), null, 0, recording = true).overlay)
        assertEquals(Overlay.Listening(""), p.present(frame(Overlay.Answer("q", "a", speaking = true)), Verbosity.MEDIUM, HudNav(), null, 0, recording = true).overlay)
        assertEquals(Overlay.Listening("milk"), p.present(frame(Overlay.Listening("milk")), Verbosity.MEDIUM, HudNav(), null, 0, recording = true).overlay)
        assertEquals(Overlay.Listening(""), HudPresenter().present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 0, recording = true).overlay, "final review I1: a Confirm waits")
        assertEquals(null, HudPresenter().present(frame(null), Verbosity.MEDIUM, HudNav(), null, 0).overlay)
    }

    /** Fix round 1: a local toast or splash never hides Listening while recording (gestures route on what is shown). */
    @Test fun aLocalOverlayDoesNotOutrankListeningWhileRecording() {
        for (local in listOf(LocalOverlay.Toast("Capture resumed", 0), LocalOverlay.Splash(0))) {
            assertEquals(Overlay.Listening(""), HudPresenter().present(frame(null), Verbosity.MEDIUM, HudNav(), local, 1, recording = true).overlay)
        }
        assertEquals(Overlay.Toast("Capture resumed"), HudPresenter().present(frame(null), Verbosity.MEDIUM, HudNav(), LocalOverlay.Toast("Capture resumed", 0), 1).overlay)
    }

    /** Final review I1: a Confirm that arrives while recording waits under Listening; its 15 s start when it shows. */
    @Test fun aConfirmArrivingWhileRecordingWaitsAndGetsItsFullWindow() {
        val p = HudPresenter()
        assertEquals(Overlay.Listening(""), p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 0, recording = true).overlay)
        assertEquals(Overlay.Listening(""), p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 20_000, recording = true).overlay)
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 20_001).overlay, "shown once recording ends")
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 34_900).overlay, "never expired unseen")
        assertNull(p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 35_001).overlay)
    }

    /** Final review I1, other order: a shown Confirm hidden by a recording gets a fresh window when it shows again. */
    @Test fun aShownConfirmHiddenByRecordingGetsAFreshWindowAfter() {
        val p = HudPresenter()
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 0).overlay)
        assertEquals(Overlay.Listening(""), p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 10_000, recording = true).overlay)
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 30_000).overlay)
        assertEquals(confirm, p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 44_900).overlay)
        assertNull(p.present(frame(confirm), Verbosity.MEDIUM, HudNav(), null, 45_001).overlay)
    }

    /** Final review I2: a phone toast (the ask was refused or failed) shows over the glasses' own Listening; an undo toast does not. */
    @Test fun aPhoneToastOutranksListeningWhileRecording() {
        val busy = Overlay.Toast("Phone is listening \u2014 try again")
        assertEquals(busy, HudPresenter().present(frame(busy), Verbosity.SILENT, HudNav(), null, 0, recording = true).overlay)
        val undo = Overlay.Toast("Added \u00b7 double-tap undo", undoProposalId = "p")
        assertEquals(Overlay.Listening(""), HudPresenter().present(frame(undo), Verbosity.SILENT, HudNav(), null, 0, recording = true).overlay)
    }

    @Test fun undoToastVisibleForTenSeconds() {
        val undo = Overlay.Toast("Added \u00b7 double-tap undo", undoProposalId = "p")
        val p = HudPresenter()
        assertEquals(undo, p.present(frame(undo), Verbosity.SILENT, HudNav(), null, 0).overlay)
        assertEquals(undo, p.present(frame(undo), Verbosity.SILENT, HudNav(), null, 9_000).overlay)
        assertEquals(null, p.present(frame(undo), Verbosity.SILENT, HudNav(), null, 11_000).overlay)
        val plain = HudPresenter()
        plain.present(frame(Overlay.Toast("Saved")), Verbosity.MEDIUM, HudNav(), null, 0)
        assertEquals(null, plain.present(frame(Overlay.Toast("Saved")), Verbosity.MEDIUM, HudNav(), null, 4_000).overlay)
    }
}
