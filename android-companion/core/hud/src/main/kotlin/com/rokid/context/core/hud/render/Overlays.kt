package com.rokid.context.core.hud.render

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Glyph
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.HudToast
import com.rokid.context.core.hud.Label
import com.rokid.context.core.hud.OutlinedBox
import com.rokid.context.core.model.Overlay

/** One overlay at full scale in the lower band (spec §3.2–§3.3). */
@Composable
fun OverlayView(o: Overlay, confirmHighlightYes: Boolean, recording: Boolean = false) {
    when (o) {
        is Overlay.Listening -> ListeningOverlay(o.partial, recording)
        is Overlay.Answer -> AnswerCard(o)
        is Overlay.Nudge -> NudgeToast(o.text)
        is Overlay.Confirm -> ConfirmOverlay(o.title, o.detail, confirmHighlightYes)
        Overlay.PausedSplash -> PausedSplash()
        is Overlay.Toast -> HudToast(o.text)
    }
}

@Composable
fun ListeningOverlay(partial: String, recording: Boolean = false) {
    val ring by rememberInfiniteTransition(label = "listening")
        .animateFloat(Hud.TERTIARY, Hud.PRIMARY, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ring")
    Column(
        Modifier.background(Color.Black).border(Hud.STROKE_HIGHLIGHT, Hud.Green.copy(alpha = ring), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(Icons.Outlined.Mic, 36.dp, Hud.PRIMARY)
        if (partial.isNotBlank()) Label(partial, 24.sp, Hud.PRIMARY, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Label(if (recording) "tap to send · double-tap cancel" else "thinking… · double-tap cancel", 18.sp, Hud.TERTIARY)
    }
}

@Composable
fun AnswerCard(a: Overlay.Answer) {
    Column(Modifier.background(Color.Black).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Label("YOU ASKED", 18.sp, Hud.SECONDARY)
        Label(a.question, 22.sp, Hud.SECONDARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
        OutlinedBox(highlighted = true, radius = 16.dp) { Label(a.text, 26.sp, Hud.PRIMARY, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.speaking) Glyph(Icons.AutoMirrored.Outlined.VolumeUp, 20.dp, Hud.TERTIARY)
            Label(if (a.speaking) " speaking · swipe to dismiss" else "swipe to dismiss", 18.sp, Hud.TERTIARY)
        }
    }
}

@Composable
fun NudgeToast(text: String) {
    Column(
        Modifier.background(Color.Black).border(Hud.STROKE, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.NotificationsActive, 22.dp, Hud.SECONDARY)
            Label(" HEADS UP", 18.sp, Hud.SECONDARY)
        }
        Label(text, 24.sp, Hud.PRIMARY, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PausedSplash() {
    Column(
        Modifier.background(Color.Black).border(Hud.STROKE, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(Icons.Outlined.Pause, 48.dp, Hud.PRIMARY)
        Label("Capture paused", 26.sp, Hud.PRIMARY, FontWeight.Bold)
    }
}
