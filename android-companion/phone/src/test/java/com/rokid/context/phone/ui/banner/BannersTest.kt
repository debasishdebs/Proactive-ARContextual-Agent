package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
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

    /** Retention v3 (K5): the one-time notice is the LEAST severe banner, has one action (Got it) and goes once dismissed. */
    @Test fun theRetentionNoticeShowsOnceAndIsDismissible() {
        val on = BannerResolver.candidates(BannerInputs(retentionNotice = true))
        assertEquals(listOf(BannerKind.RETENTION_NOTICE), on.map { it.kind })
        assertEquals(BannerAction.DISMISS_NOTICE, on.single().action); assertEquals("Got it", on.single().actionLabel)
        assertEquals("Clean-up no longer deletes the details of your photos. Forget removes them.", on.single().title)
        assertEquals(emptyList<BannerSpec>(), BannerResolver.candidates(BannerInputs(retentionNotice = false)))
        val withOther = BannerResolver.candidates(BannerInputs(aiBlockedText = "no key", retentionNotice = true))
        assertEquals(listOf(BannerKind.AI_BLOCKED, BannerKind.RETENTION_NOTICE), withOther.map { it.kind })
    }

    @Test fun dismissingStoresItForever() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences(com.rokid.context.phone.ui.shell.RetentionNoticeControl.PREFS, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val c = com.rokid.context.phone.ui.shell.RetentionNoticeControl.prefs(ctx)
        assertEquals(true, kotlinx.coroutines.runBlocking { c.pending.first() })
        c.dismiss()
        assertEquals(false, kotlinx.coroutines.runBlocking { c.pending.first() })
        assertEquals("a new process still sees it dismissed", false, kotlinx.coroutines.runBlocking { com.rokid.context.phone.ui.shell.RetentionNoticeControl.prefs(ctx).pending.first() })
    }
}
