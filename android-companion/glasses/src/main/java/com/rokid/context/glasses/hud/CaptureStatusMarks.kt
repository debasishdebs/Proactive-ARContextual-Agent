package com.rokid.context.glasses.hud

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** HUD text for the two capture marks (spec P1.5 "Cooling" icon, P1.6 "capture degraded" mark). */
object CaptureStatusCopy {
    const val COOLING = "Cooling"
    const val DEGRADED = "! capture degraded"
    /** Fix wave H3: the per-keyframe blink; plain ASCII (no emoji on the HUD font). */
    const val CUE = "(o)"
}

/** Pure: the blink's text while a keyframe was just saved, else null. */
fun captureCueMark(active: Boolean): String? = if (active) CaptureStatusCopy.CUE else null

/** Pure: which marks to show, in draw order. */
fun captureStatusMarks(cooling: Boolean, degraded: Boolean): List<String> = buildList {
    if (cooling) add(CaptureStatusCopy.COOLING)
    if (degraded) add(CaptureStatusCopy.DEGRADED)
}

/**
 * Hook for G3b: place this inside the HUD and feed it from the capture session's status (heat level STOP -> [cooling],
 * bind-failed fallback -> [degraded]). Small, bright-on-black text (black is see-through on the Rokid display); draws
 * nothing when both are false.
 */
@Composable
fun CaptureStatusMarks(cooling: Boolean, degraded: Boolean, modifier: Modifier = Modifier) {
    val marks = captureStatusMarks(cooling, degraded)
    if (marks.isEmpty()) return
    Row(modifier.padding(4.dp)) {
        marks.forEach { BasicText(it, modifier = Modifier.padding(end = 8.dp), style = TextStyle(color = Color.White, fontSize = 12.sp)) }
    }
}

/** The per-keyframe blink ([CaptureBlink]): a small bright mark in the corner it is placed in; nothing when idle. */
@Composable
fun CaptureCueMark(active: Boolean, modifier: Modifier = Modifier) {
    val mark = captureCueMark(active) ?: return
    BasicText(mark, modifier = modifier.padding(4.dp), style = TextStyle(color = Color.White, fontSize = 14.sp))
}

/** Slice 3a Task 30: the mark cue ("✓" or "mark undone", 600 ms, local); nothing when idle. */
@Composable
fun MarkCueMark(text: String?, modifier: Modifier = Modifier) {
    val t = text ?: return
    BasicText(t, modifier = modifier.padding(4.dp), style = TextStyle(color = Color.White, fontSize = 14.sp))
}
