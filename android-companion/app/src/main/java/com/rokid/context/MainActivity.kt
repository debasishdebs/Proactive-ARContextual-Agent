package com.rokid.context

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.ui.config.ConfigHubScreen
import com.rokid.context.ui.diagnostics.DiagnosticsScreen
import com.rokid.context.ui.home.HomeScreen
import com.rokid.context.ui.migration.MigrationStatus
import com.rokid.context.ui.migration.MigrationStatusCard
import com.rokid.context.ui.onboarding.OnboardingScreen
import com.rokid.context.ui.onboarding.OnboardingState
import com.rokid.context.work.OrchestrationScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OrchestrationScheduler(this).apply {
            scheduleFrameIngest()
            scheduleCritic()
        }
        setContent {
            MaterialTheme {
                AppRoot()
            }
        }
    }
}

private enum class AppRoute {
    ONBOARDING,
    HOME,
    CONFIG,
    DIAGNOSTICS,
    MIGRATION,
}

@Composable
private fun AppRoot() {
    var route by rememberSaveable { mutableStateOf(AppRoute.ONBOARDING) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        when (route) {
            AppRoute.ONBOARDING -> {
                OnboardingScreen.Render(
                    state = OnboardingState(
                        permissionsReady = false,
                        providerReady = false,
                        optionalPermissionsSkipped = false,
                    ),
                    onContinue = { route = AppRoute.HOME },
                )
            }

            AppRoute.HOME -> {
                HomeScreen.Render(
                    showMigrationCard = true,
                    onConfig = { route = AppRoute.CONFIG },
                    onDiagnostics = { route = AppRoute.DIAGNOSTICS },
                    onMigration = { route = AppRoute.MIGRATION },
                )
            }

            AppRoute.CONFIG -> {
                ConfigHubScreen.Render(
                    showAdvancedReadOnly = true,
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.DIAGNOSTICS -> {
                DiagnosticsScreen.Render(
                    verboseLogsEnabled = true,
                    onBack = { route = AppRoute.HOME },
                )
            }

            AppRoute.MIGRATION -> {
                MigrationStatusCard.Render(
                    status = MigrationStatus(active = true, progressPercent = 40),
                    onBack = { route = AppRoute.HOME },
                )
            }
        }
    }
}
