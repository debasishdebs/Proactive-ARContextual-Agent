package com.rokid.context.core.hud.render

import androidx.compose.ui.Alignment
import com.rokid.context.core.model.HudPosition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "4:12" until [nextAtMs]; "--:--" when unknown or due. */
fun formatCountdown(nextAtMs: Long?, nowMs: Long): String {
    if (nextAtMs == null || nextAtMs <= nowMs) return "--:--"
    val s = (nextAtMs - nowMs + 999) / 1_000
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** HH:mm in [zone], resolved at display time. */
fun formatClock(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochMilli(atMs))

fun formatInterval(sec: Int): String = if (sec % 60 == 0) "${sec / 60} min" else "$sec s"

/** 3×3 HUD position (spec §2.4 Glasses settings) → where [com.rokid.context.core.hud.Scaled] anchors the page. */
fun HudPosition.alignment(): Alignment = when (this) {
    HudPosition.TOP_LEFT -> Alignment.TopStart
    HudPosition.TOP_CENTER -> Alignment.TopCenter
    HudPosition.TOP_RIGHT -> Alignment.TopEnd
    HudPosition.CENTER_LEFT -> Alignment.CenterStart
    HudPosition.CENTER -> Alignment.Center
    HudPosition.CENTER_RIGHT -> Alignment.CenterEnd
    HudPosition.BOTTOM_LEFT -> Alignment.BottomStart
    HudPosition.BOTTOM_CENTER -> Alignment.BottomCenter
    HudPosition.BOTTOM_RIGHT -> Alignment.BottomEnd
}
