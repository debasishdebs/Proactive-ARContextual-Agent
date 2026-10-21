package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.ui.components.BannerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerResolverTest {
    @Test fun healthyStateHasNoBanner() = assertNull(BannerResolver.resolve(BannerInputs()))

    @Test fun severityOrderMatchesSpec() {
        val all = BannerInputs(
            aiBlockedText = "AI key rejected", link = LinkState.OFFLINE, capture = CaptureState.Deferred("Low battery"),
            pipelineEnabled = false, index = IndexState.NEEDED, storagePct = 75,
        )
        assertEquals(
            listOf(BannerKind.AI_BLOCKED, BannerKind.GLASSES_DISCONNECTED, BannerKind.CAPTURE_DEFERRED, BannerKind.CAPTURE_PAUSED, BannerKind.INDEX, BannerKind.STORAGE),
            BannerResolver.candidates(all).map { it.kind },
        )
        assertEquals(BannerKind.AI_BLOCKED, BannerResolver.resolve(all)?.kind)
        assertEquals(
            listOf(BannerKind.PROTOCOL_MISMATCH, BannerKind.CAPTURE_DEFERRED),
            BannerResolver.candidates(BannerInputs(link = LinkState.VERSION_MISMATCH, capture = CaptureState.Deferred("Low battery"))).map { it.kind },
        )
    }

    /** G7: Home says asks are paused while Battery Saver is on; it ranks right after AI unavailable. */
    @Test fun batterySaverBannerSaysAsksArePaused() {
        val b = BannerResolver.resolve(BannerInputs(batterySaver = true))!!
        assertEquals(BannerKind.BATTERY_SAVER, b.kind)
        assertEquals("Battery Saver is on \u2014 asks are paused", b.title)
        assertEquals(BannerTone.AMBER, b.tone)
        assertEquals(
            listOf(BannerKind.AI_BLOCKED, BannerKind.BATTERY_SAVER, BannerKind.GLASSES_DISCONNECTED),
            BannerResolver.candidates(BannerInputs(aiBlockedText = "x", batterySaver = true, link = LinkState.OFFLINE)).map { it.kind },
        )
    }

    @Test fun disconnectedVariantsOfferReconnect() {
        val offline = BannerResolver.resolve(BannerInputs(link = LinkState.OFFLINE))!!
        assertEquals("Glasses disconnected", offline.title); assertEquals("Reconnect", offline.actionLabel); assertEquals(BannerAction.RECONNECT, offline.action)
        val closed = BannerResolver.resolve(BannerInputs(link = LinkState.CLOSED_ON_GLASSES))!!
        assertEquals("Open on glasses", closed.actionLabel); assertEquals(BannerAction.RECONNECT, closed.action)
        assertEquals(BannerKind.GLASSES_DISCONNECTED, BannerResolver.resolve(BannerInputs(link = LinkState.NOT_AUTHORIZED))?.kind)
        for (quiet in listOf(LinkState.CONNECTED, LinkState.CONNECTING, LinkState.PAUSED_BT)) assertNull(quiet.name, BannerResolver.resolve(BannerInputs(link = quiet)))
    }

    @Test fun pausedByPipelineOrByCaptureStateOffersResume() {
        val p = BannerResolver.resolve(BannerInputs(pipelineEnabled = false))!!
        assertEquals(BannerKind.CAPTURE_PAUSED, p.kind); assertEquals(BannerTone.SLATE, p.tone); assertEquals(BannerAction.RESUME, p.action)
        val rec = CaptureState.Paused(listOf(PauseRecord("p1", PauseOrigin.GLASSES, 1)))
        assertEquals(BannerKind.CAPTURE_PAUSED, BannerResolver.resolve(BannerInputs(capture = rec))?.kind)
        assertEquals(BannerTone.AMBER, BannerResolver.resolve(BannerInputs(capture = CaptureState.Deferred("Low battery")))?.tone)
    }

    @Test fun storageThresholds() {
        assertNull(BannerResolver.resolve(BannerInputs(storagePct = 69)))
        assertEquals(BannerTone.AMBER, BannerResolver.resolve(BannerInputs(storagePct = 70))?.tone)
        assertEquals(BannerTone.AMBER, BannerResolver.resolve(BannerInputs(storagePct = 89))?.tone)
        assertEquals(BannerTone.CORAL, BannerResolver.resolve(BannerInputs(storagePct = 90))?.tone)
        assertEquals("Memory storage 92% full \u00b7 raise the limit in Settings \u2192 Memory", BannerResolver.resolve(BannerInputs(storagePct = 92))?.title)
    }

    @Test fun aiBlockedBannerTruncatesLongText() {
        val b = BannerResolver.resolve(BannerInputs(aiBlockedText = "x".repeat(200) + "\nstack"))!!
        assertTrue(b.title.startsWith("AI unavailable · "))
        assertTrue(b.title.length <= "AI unavailable · ".length + 60)
        assertTrue(b.title.endsWith("…"))
        assertEquals(BannerAction.FIX_AI, b.action)
    }

    /** Phase 7 (closes the P6 T4 ruling): every paused banner offers Resume, which resumes the ids shown. */
    @Test fun hubPauseWithPipelineOnShowsPausedBannerWithResume() {
        val rec = CaptureState.Paused(listOf(PauseRecord("p1", PauseOrigin.GLASSES, 1)))
        val b = BannerResolver.resolve(BannerInputs(capture = rec, pipelineEnabled = true))!!
        assertEquals(BannerKind.CAPTURE_PAUSED, b.kind)
        assertEquals(BannerAction.RESUME, b.action); assertEquals("Resume", b.actionLabel)
        val both = BannerResolver.resolve(BannerInputs(capture = rec, pipelineEnabled = false))!!
        assertEquals(BannerAction.RESUME, both.action)
    }

    /** Minimised Option A: nothing to fix while minimised, so no glasses banner. */
    @Test fun minimisedOnGlassesNeedsNoBanner() {
        assertEquals(null, BannerResolver.candidates(BannerInputs(link = LinkState.MINIMISED)).firstOrNull { it.kind == BannerKind.GLASSES_DISCONNECTED })
    }

    /** Phase 8 C3: closing the glasses app force-stops it, which stops capture until it is reopened. */
    @Test fun closedOnGlassesSaysCaptureStopped() {
        val closed = BannerResolver.resolve(BannerInputs(link = LinkState.CLOSED_ON_GLASSES))!!
        assertEquals("Closed on glasses \u00b7 capture stopped", closed.title)
        assertEquals("Open on glasses", closed.actionLabel)
    }

    @Test fun storageBannerPointsToSettings() {
        val amber = BannerResolver.resolve(BannerInputs(storagePct = 75))!!
        val coral = BannerResolver.resolve(BannerInputs(storagePct = 95))!!
        assertEquals("Memory storage 75% full \u00b7 raise the limit in Settings \u2192 Memory", amber.title)
        assertEquals("Memory storage 95% full \u00b7 raise the limit in Settings \u2192 Memory", coral.title)
        assertEquals(BannerTone.AMBER, amber.tone); assertEquals(BannerTone.CORAL, coral.tone)
    }
}
