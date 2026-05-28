package com.rokid.context.ui.onboarding

data class OnboardingState(
    val permissionsReady: Boolean,
    val providerReady: Boolean,
    val optionalPermissionsSkipped: Boolean,
)

object OnboardingScreen {
    fun canFinish(state: OnboardingState): Boolean = state.permissionsReady && state.providerReady

    fun showLimitedModeBanner(state: OnboardingState): Boolean = state.optionalPermissionsSkipped
}
