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
    const val COOLING = "❄ Cooling"
    const val DEGRADED = "! capture degraded"
}

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
