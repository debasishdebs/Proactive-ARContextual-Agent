package com.rokid.context.core.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Monochrome HUD for the green Micro-LED (spec §3.3). Rules: black = transparent, outlines not fills,
 * hierarchy by brightness (100 / 60 / 35 %), centre kept clear, numbers in tabular digits.
 */
object Hud {
    val Green = Color(0xFF3CFF6E)
    const val PRIMARY = 1f
    const val SECONDARY = 0.6f
    const val TERTIARY = 0.35f
    val STROKE: Dp = 2.dp
    val STROKE_HIGHLIGHT: Dp = 3.dp
    const val MIN_SCALE = 0.3f
    const val MAX_SCALE = 1f
    const val DEFAULT_SCALE = 0.4f
}

fun clampHudScale(scale: Float): Float = if (scale.isNaN()) Hud.DEFAULT_SCALE else scale.coerceIn(Hud.MIN_SCALE, Hud.MAX_SCALE)

@Composable
fun Glyph(icon: ImageVector, size: Dp, level: Float) {
    Image(rememberVectorPainter(icon), contentDescription = null, colorFilter = ColorFilter.tint(Hud.Green), modifier = Modifier.size(size).alpha(level))
}

@Composable
fun Label(
    text: String,
    size: TextUnit,
    level: Float,
    weight: FontWeight = FontWeight.Medium,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(color = Hud.Green.copy(alpha = level), fontSize = size, fontWeight = weight, fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum"),
    )
}

/** Draws [content] in a box of [scale] × the screen at [alignment], with everything shrunk to match. */
@Composable
fun BoxScope.Scaled(scale: Float, alignment: Alignment, content: @Composable () -> Unit) {
    val s = clampHudScale(scale)
    val base = LocalDensity.current
    Box(Modifier.align(alignment).fillMaxWidth(s).fillMaxHeight(s)) {
        CompositionLocalProvider(LocalDensity provides Density(base.density * s, base.fontScale)) { content() }
    }
}

/** Outlined container: 2 dp at 60 % normally, 3 dp at 100 % when [highlighted]. */
@Composable
fun OutlinedBox(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    radius: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .border(if (highlighted) Hud.STROKE_HIGHLIGHT else Hud.STROKE, Hud.Green.copy(alpha = if (highlighted) Hud.PRIMARY else Hud.SECONDARY), RoundedCornerShape(radius))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        content = content,
    )
}

@Composable
fun HudToast(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black).border(Hud.STROKE, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Icons.Outlined.Check, 28.dp, Hud.PRIMARY)
        Label(" $text", 26.sp, Hud.PRIMARY, FontWeight.Bold, maxLines = 2) // BUG-R2-5: "undo" was clipped at 480 px
    }
}
