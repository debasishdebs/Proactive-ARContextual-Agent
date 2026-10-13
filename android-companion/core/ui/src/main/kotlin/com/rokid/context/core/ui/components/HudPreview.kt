package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.hud.render.HudConnection
import com.rokid.context.core.hud.render.HudModel
import com.rokid.context.core.hud.render.HudNav
import com.rokid.context.core.hud.render.HudPresenter
import com.rokid.context.core.hud.render.HudRoot
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.StateFrame

object HudPreviewFixtures {
    /** A typical moment: capturing, next frame in 4:12, a nudge one-liner and two Today rows. */
    fun frame(nowMs: Long): StateFrame = StateFrame(
        seq = 1, capture = CaptureState.Running, nextCaptureAtMs = nowMs + 252_000, intervalSec = 300, glassesBatteryPct = 82,
        phoneLinked = true, aiHealth = Health.Ok, latestNudge = NudgeLine("Milk looked almost empty", nowMs - 60_000),
        today = listOf(MemoryLine("m1", nowMs - 3_600_000, "Keys on the hallway shelf"), MemoryLine("m2", nowMs - 7_200_000, "Coffee with Priya")),
    )
}

private val HUD_WIDTH = 320.dp
private val HUD_HEIGHT = 427.dp

/**
 * spec §2.3 `HudPreview`: the real `:core:hud` pages on black at 3:4, laid out at the glasses' logical size and
 * scaled to fit, so verbosity, scale and position changes look as they will on the HUD.
 */
@Composable
fun HudPreview(
    settings: HudSettings,
    modifier: Modifier = Modifier,
    nowMs: Long = 0,
    frame: StateFrame = HudPreviewFixtures.frame(nowMs),
    page: HudPage = HudPage.GLANCE,
) {
    val nav = HudNav(page = page)
    val view = HudPresenter().present(frame, settings.verbosity, nav, null, nowMs)
    val model = HudModel(
        view = view, frame = frame, settings = settings, connection = HudConnection.LIVE, batteryPct = frame.glassesBatteryPct,
        paused = frame.capture is CaptureState.Paused, nextCaptureAtMs = frame.nextCaptureAtMs, nowMs = nowMs, nav = nav, confirmHighlightYes = true,
    )
    BoxWithConstraints(modifier.aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black).testTag("hud_preview"), contentAlignment = Alignment.Center) {
        val k = maxWidth / HUD_WIDTH
        Box(Modifier.requiredSize(HUD_WIDTH, HUD_HEIGHT).graphicsLayer { scaleX = k; scaleY = k }) { HudRoot(model) }
    }
}
