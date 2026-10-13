package com.rokid.context.core.hud.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.Label
import com.rokid.context.core.hud.OutlinedBox
import com.rokid.context.core.hud.Scaled
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.StateFrame

/** Everything one HUD render needs; built by MainActivity from the controller, presenter and local state. */
data class HudModel(
    val view: HudView,
    val frame: StateFrame?,
    val settings: HudSettings,
    val connection: HudConnection,
    val batteryPct: Int?,
    /** The glasses' own pause set has an active record (glasses authority, spec §1.7). */
    val paused: Boolean,
    /** Local scheduler, glasses clock: the countdown survives link drops. */
    val nextCaptureAtMs: Long?,
    val nowMs: Long,
    val nav: HudNav,
    val confirmHighlightYes: Boolean,
)

/**
 * HUD root (spec §3.2–§3.3): black is transparent on the display; the page is bottom-anchored inside [Scaled] at
 * `HudSettings.scale`/`position`; an overlay draws at full scale in the lower band, keeping the centre clear.
 */
@Composable
fun HudRoot(m: HudModel) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (m.connection == HudConnection.OUTDATED) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) {
                OutlinedBox(highlighted = true) { Label("Update Proactive Context", 26.sp, Hud.PRIMARY, FontWeight.Bold) }
            }
            return@Box
        }
        Scaled(m.settings.scale, m.settings.position.alignment()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                val page = m.view.page
                if (page == null) {
                    RecDot(m)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (page) {
                            HudPage.GLANCE -> GlancePage(m)
                            HudPage.TODAY -> TodayPage(m)
                            HudPage.STATUS -> StatusPage(m)
                        }
                        PageDots(pagesFor(m.settings.verbosity), page)
                    }
                }
            }
        }
        val overlay = m.view.overlay
        if (overlay != null) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)) { OverlayView(overlay, m.confirmHighlightYes) }
    }
}

@Composable
private fun PageDots(pages: List<HudPage>, current: HudPage) {
    if (pages.size < 2) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in pages) {
            val on = p == current
            Box(Modifier.size(if (on) 10.dp else 8.dp).border(2.dp, Hud.Green.copy(alpha = if (on) Hud.PRIMARY else Hud.TERTIARY), CircleShape))
        }
    }
}
