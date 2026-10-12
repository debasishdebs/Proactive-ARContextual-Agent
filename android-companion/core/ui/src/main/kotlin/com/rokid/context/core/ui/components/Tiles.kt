package com.rokid.context.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.ui.theme.PcColors

/** SoftCard with a 36 dp chip, a big value, optional unit and a label (Live Fit `MetricTile`). */
@Composable
fun MetricTile(
    icon: ImageVector,
    colors: Pair<Color, Color>,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    onClick: (() -> Unit)? = null,
) {
    SoftCard(modifier, onClick) {
        Column(Modifier.padding(14.dp)) {
            IconChip(icon, colors, size = 36.dp, shapeRadius = 10.dp)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PcColors.Ink)
                if (unit != null) { Spacer(Modifier.width(4.dp)); Text(unit, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft) }
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
        }
    }
}

/** Top-of-screen gradient header with a title, subline and trailing actions. */
@Composable
fun GradientHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().background(PcColors.HeaderGradient).statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = PcColors.Ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
    }
}

enum class BannerTone(val colors: Pair<Color, Color>) {
    CORAL(PcColors.ChipCoral), AMBER(PcColors.ChipAmber), SLATE(PcColors.ChipSlate), SKY(PcColors.ChipSky)
}

/** One-line status banner with an optional action (spec §2.2). */
@Composable
fun Banner(tone: BannerTone, title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tone.colors.first).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = tone.colors.second, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, color = tone.colors.second, fontWeight = FontWeight.SemiBold) }
        }
    }
}
