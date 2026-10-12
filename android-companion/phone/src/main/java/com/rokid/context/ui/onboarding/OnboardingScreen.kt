package com.rokid.context.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

data class OnboardingState(
    val permissionsReady: Boolean,
    val providerReady: Boolean,
    val optionalPermissionsSkipped: Boolean,
)

object OnboardingScreen {
    fun canFinish(state: OnboardingState): Boolean = state.permissionsReady && state.providerReady

    fun showLimitedModeBanner(state: OnboardingState): Boolean = state.optionalPermissionsSkipped

    @Composable
    fun Render(state: OnboardingState, onContinue: () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Welcome", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup required for minimum E2E")
                    Text("- Permissions ready: ${state.permissionsReady}")
                    Text("- Provider configured: ${state.providerReady}")
                }
            }
            if (showLimitedModeBanner(state)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Limited Mode: optional permissions skipped",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Button(
                enabled = canFinish(state),
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Continue")
            }
        }
    }
}
