package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class BannersTest {
    private val now = 10_000_000L
    private val g = PauseRecord("g", PauseOrigin.GLASSES, 1, null)

    @Test fun stillPausedAfterResumeShowsSecondPrompt() {
        val b = BannerResolver.resolve(
            BannerInputs(capture = CaptureState.Paused(listOf(g)), pipelineEnabled = false, pauses = listOf(g), lastResumeAtMs = now - 60_000, nowMs = now),
        )!!
        assertEquals(BannerKind.CAPTURE_PAUSED, b.kind)
        assertEquals("Still paused on glasses — Resume?", b.title)
        assertEquals(BannerAction.RESUME, b.action)
        assertEquals("Resume", b.actionLabel)
    }

    @Test fun resumeOlderThanTenMinutesIsThePlainBanner() {
        val b = BannerResolver.resolve(
            BannerInputs(pipelineEnabled = false, pauses = listOf(g), lastResumeAtMs = now - 10 * 60_000 - 1, nowMs = now),
        )!!
        assertEquals("Capture paused", b.title)
    }

    @Test fun glassesOnlyPauseHasResumeAction() {
        val b = BannerResolver.resolve(BannerInputs(capture = CaptureState.Paused(listOf(g)), pipelineEnabled = false, pauses = listOf(g)))!!
        assertEquals("Capture paused", b.title)
        assertEquals(BannerAction.RESUME, b.action)
    }
}
