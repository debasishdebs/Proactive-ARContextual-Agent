package com.rokid.context.phone.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.theme.PcColors

/** One-step onboarding (Phase 4). Phase 7 replaces it with the full wizard (R-19). */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(PcColors.HeaderGradient).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconChip(GlassesIcon, PcColors.ChipMint, size = 96.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(24.dp))
        Text("Welcome to Proactive Context", style = MaterialTheme.typography.headlineMedium, color = PcColors.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Your glasses capture a few quiet moments. Your phone turns them into memories you can ask about.",
            style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        GradientButton("Get started", onClick = onGetStarted, modifier = Modifier.fillMaxWidth())
    }
}
