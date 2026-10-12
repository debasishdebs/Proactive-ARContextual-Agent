package com.rokid.context.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Live Fit palette (spec §2.3): white surfaces, mint/sky accents, pastel icon chips. Light only. */
object PcColors {
    val Mint = Color(0xFF14C3A2)
    val MintDeep = Color(0xFF0E9F86)
    val Sky = Color(0xFF3D8BFF)
    val Ink = Color(0xFF15181D)
    val InkSoft = Color(0xFF6B7280)
    val Line = Color(0xFFECEFF3)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSoft = Color(0xFFF6F8FA)

    // Pastel chip pairs (background, foreground).
    val ChipMint = Color(0xFFE3F8F3) to Color(0xFF0E9F86)
    val ChipSky = Color(0xFFE6F0FF) to Color(0xFF2F6FE0)
    val ChipCoral = Color(0xFFFFEAE5) to Color(0xFFE5583B)
    val ChipAmber = Color(0xFFFFF4DC) to Color(0xFFD08A00)
    val ChipViolet = Color(0xFFF0EAFF) to Color(0xFF7550E0)
    val ChipRose = Color(0xFFFFE8F1) to Color(0xFFD63F7A)
    val ChipSlate = Color(0xFFEEF1F5) to Color(0xFF55606E)

    val HeaderGradient = Brush.verticalGradient(listOf(Color(0xFFDDF7F0), Color(0xFFE8F1FF), Color(0xFFFFFFFF)))
    val PrimaryGradient = Brush.linearGradient(listOf(Mint, Sky))
}

private val colors = lightColorScheme(
    primary = PcColors.Mint,
    onPrimary = Color.White,
    primaryContainer = PcColors.ChipMint.first,
    onPrimaryContainer = PcColors.MintDeep,
    secondary = PcColors.Sky,
    background = PcColors.Surface,
    onBackground = PcColors.Ink,
    surface = PcColors.Surface,
    onSurface = PcColors.Ink,
    surfaceVariant = PcColors.SurfaceSoft,
    onSurfaceVariant = PcColors.InkSoft,
    outline = PcColors.Line,
    outlineVariant = PcColors.Line,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp))

@Composable
fun PcTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
