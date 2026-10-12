package com.rokid.context.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Glyph
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.Label

/** Outlined ✓ Yes / ✕ No (Live Fit `ConfirmOverlay`); the highlight is brighter and thicker, never filled. */
@Composable
fun ConfirmOverlay(title: String, detail: String?, highlightYes: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black).border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(title, 28.sp, Hud.PRIMARY, FontWeight.Bold)
        if (!detail.isNullOrBlank()) Label(detail, 22.sp, Hud.SECONDARY)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Choice(Icons.Outlined.Check, "Yes", selected = highlightYes)
            Choice(Icons.Outlined.Close, "No", selected = !highlightYes)
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, selected: Boolean) {
    val level = if (selected) Hud.PRIMARY else Hud.TERTIARY
    Row(
        Modifier.border(if (selected) 4.dp else 2.dp, Hud.Green.copy(alpha = level), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 28.dp, level)
        Label(" $label", 26.sp, level, FontWeight.Bold)
    }
}
