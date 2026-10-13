package com.rokid.context.phone.ui.onboarding

import androidx.compose.runtime.Composable
import com.rokid.context.phone.ui.shell.PhoneDeps

/** The onboarding wizard (R-19, spec §2.4). Placeholder (Stream 0): today's one-step welcome. Stream E fills it. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun OnboardingRoute(deps: PhoneDeps, onDone: () -> Unit) {
    WelcomeScreen(onGetStarted = onDone)
}
