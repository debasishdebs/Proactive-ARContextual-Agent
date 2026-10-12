package com.rokid.context.phone.hub

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.link.ClockOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateFrameBuilderTest {
    private val now = 1_793_000_000_000L
    private fun inputs(
        link: LinkState = LinkState.CONNECTED,
        pauses: List<PauseRecord> = emptyList(),
        offset: ClockOffset? = ClockOffset(0),
        today: List<MemoryLine> = emptyList(),
    ) = HubInputs(
        seq = 3, nowMs = now, link = link, activePauses = pauses, offset = offset, settings = HudSettings(captureIntervalSec = 120),
        glassesBatteryPct = 64, nextCaptureAtMs = now + 60_000, queueDepth = 2, lastFrameAtMs = now - 5_000, today = today,
        aiHealth = Health.Ok, overlay = null, error = UserFacingErrors.StorageFull,
    )

    @Test fun noPausesIsRunning() {
        val f = StateFrameBuilder.build(inputs())
        assertEquals(CaptureState.Running, f.capture)
        assertEquals(3L, f.seq); assertEquals(120, f.intervalSec); assertEquals(64, f.glassesBatteryPct)
        assertEquals(2, f.queueDepth); assertEquals(now - 5_000, f.lastSentAtMs)
        assertEquals("Phone storage full", f.error)
    }

    /** Spec §5.5: the glasses enforce pauses on their own clock, so records go out converted. */
    @Test fun pausesGoOutInGlassesClock() {
        val r = PauseRecord("p1", PauseOrigin.PHONE, now - 1_000, now + 60_000)
        val o = ClockOffset(7_200_000)
        val f = StateFrameBuilder.build(inputs(pauses = listOf(r), offset = o))
        assertEquals(CaptureState.Paused(listOf(o.recordToGlasses(r))), f.capture)
        assertEquals(listOf(o.recordToGlasses(r)), StateFrameBuilder.settingsFor(HudSettings(), listOf(r), o).pauses)
    }

    @Test fun withoutAnOffsetPausesAreSentUnchanged() {
        val r = PauseRecord("p1", PauseOrigin.PHONE, now, null)
        assertEquals(CaptureState.Paused(listOf(r)), StateFrameBuilder.build(inputs(pauses = listOf(r), offset = null)).capture)
    }

    @Test fun phoneLinkedOnlyWhenConnected() {
        assertTrue(StateFrameBuilder.build(inputs(link = LinkState.CONNECTED)).phoneLinked)
        assertFalse(StateFrameBuilder.build(inputs(link = LinkState.PAUSED_BT)).phoneLinked)
    }

    @Test fun todayIsCappedAtEight() {
        val lines = (1..12).map { MemoryLine("m$it", now - it, "memory $it") }
        assertEquals(8, StateFrameBuilder.build(inputs(today = lines)).today.size)
    }

    @Test fun aiHealthComesFromTheNewestRecentTrace() {
        fun t(state: String, ago: Long, note: String = "") = SummaryTrace("s$ago", state, now - ago, note)
        assertEquals(Health.Ok, AiHealthRule.from(emptyList(), now))
        assertEquals(Health.Blocked("AI key rejected"), AiHealthRule.from(listOf(t("FAILED", 1_000, UserFacingErrors.AiKeyRejected.line)), now))
        assertEquals(Health.Degraded("AI unavailable"), AiHealthRule.from(listOf(t("RETRY", 1_000, UserFacingErrors.AiUnavailable.line)), now))
        assertEquals(Health.Ok, AiHealthRule.from(listOf(t("FAILED", 5_000, UserFacingErrors.AiKeyRejected.line), t("TRUSTED", 1_000)), now))
        assertEquals("stale", Health.Ok, AiHealthRule.from(listOf(t("FAILED", 31 * 60_000L, UserFacingErrors.AiKeyRejected.line)), now))
    }

    /** Notes written before Task 2 may hold raw provider text: it never reaches the HUD. */
    @Test fun rawLegacyNoteNeverReachesTheHud() = assertEquals(
        Health.Blocked("AI unavailable"),
        AiHealthRule.from(listOf(SummaryTrace("s", "FAILED", now - 1_000, "Azure OpenAI request failed: 500 {\"error\":1}")), now),
    )
}
