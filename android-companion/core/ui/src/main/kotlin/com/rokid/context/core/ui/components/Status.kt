package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors
import kotlin.math.roundToInt

/** Bands for [ConfidenceChip]: ≥ 80 % mint, ≥ 50 % amber, otherwise coral; NaN/infinite = unknown (slate, "—"). */
object Confidence {
    fun percent(value: Double): Int? = if (value.isNaN() || value.isInfinite()) null else (value.coerceIn(0.0, 1.0) * 100).roundToInt()

    fun colors(value: Double): Pair<Color, Color> = when (val p = percent(value)) {
        null -> PcColors.ChipSlate
        else -> if (p >= 80) PcColors.ChipMint else if (p >= 50) PcColors.ChipAmber else PcColors.ChipCoral
    }

    fun label(value: Double): String = percent(value)?.let { "$it%" } ?: "—"
}

@Composable
fun ConfidenceChip(confidence: Double, modifier: Modifier = Modifier) {
    val (bg, fg) = Confidence.colors(confidence)
    Text(
        Confidence.label(confidence),
        style = MaterialTheme.typography.labelMedium,
        color = fg,
        modifier = modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Thin capture status row (spec §2.4 Activity): live dot · text · glasses battery · mini pause/resume. */
@Composable
fun StatusStrip(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    batteryPct: Int? = null,
    paused: Boolean = false,
    onTogglePause: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PcColors.SurfaceSoft)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) PcColors.Mint else PcColors.ChipSlate.second))
        Spacer(Modifier.width(8.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (batteryPct != null) {
            Icon(Icons.Outlined.BatteryStd, contentDescription = null, tint = PcColors.InkSoft, modifier = Modifier.size(16.dp))
            Text("$batteryPct%", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
            Spacer(Modifier.width(8.dp))
        }
        if (onTogglePause != null) {
            val label = if (paused) "Resume capture" else "Pause capture"
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(PcColors.Surface)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, contentDescription = label, tint = PcColors.Ink, modifier = Modifier.size(18.dp))
            }
        }
    }
}
