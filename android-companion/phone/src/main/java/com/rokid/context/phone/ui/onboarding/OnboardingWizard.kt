package com.rokid.context.phone.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.IconChip
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.shell.PhoneDeps

/** The onboarding wizard (R-19, spec §2.4): seven steps, every one skippable or optional. */
@Composable
fun OnboardingRoute(deps: PhoneDeps, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val od = androidx.compose.runtime.remember { OnboardingDeps.from(deps) }
    val vm = viewModel { OnboardingViewModel(od, saved = createSavedStateHandle()) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = ctx.findActivity()
    val foreground = LocalLifecycleOwner.current.lifecycle
    fun inFront() = foreground.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun canAskAgain(permission: String) = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onMicResult(granted, canAskAgain(Manifest.permission.RECORD_AUDIO), inFront())
        // Not deps.onMicGranted(): it probes, and a grant can land while the app is only STARTED. The VM probes now or on resume.
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.onNotificationsResult(granted, if (Build.VERSION.SDK_INT >= 33) canAskAgain(Manifest.permission.POST_NOTIFICATIONS) else true)
    }
    fun micGranted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun notificationsGranted() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    // Returning from system settings (or a grant that landed while not in front): re-read, then run the deferred probe.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume(micGranted(), notificationsGranted()) }
    // Entering the Permissions step with things already granted.
    LaunchedEffect(state.step) { if (state.step == Step.PERMISSIONS) vm.syncPermissions(micGranted(), notificationsGranted(), inFront()) }
    OnboardingWizard(
        state,
        vm.callbacks(
            requestMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
            requestNotifications = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onDone = onDone,
        ),
    )
}

/** What the wizard's controls call. */
class WizardCallbacks(
    val onPrimary: () -> Unit,
    val onSecondary: () -> Unit,
    val onBack: () -> Boolean,
    val requestMic: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAppSettings: () -> Unit,
    val downloadPack: () -> Unit,
    val openSpeechSettings: () -> Unit,
    val steps: StepCallbacks = StepCallbacks(),
)

@Composable
fun OnboardingWizard(state: OnboardingUiState, cb: WizardCallbacks) {
    // System back goes to the previous step; on WELCOME the handler is off and the app closes.
    BackHandler(enabled = state.step != Step.WELCOME) { cb.onBack() }
    val c = stepCopy(state.step)
    Column(Modifier.fillMaxSize().background(PcColors.HeaderGradient).systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp).testTag("step_${state.step.name}")) {
        ProgressBar(state.filledSegments)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconChip(GlassesIcon, PcColors.ChipMint, size = 96.dp, shapeRadius = 28.dp)
            Spacer(Modifier.height(24.dp))
            Text(c.title, style = MaterialTheme.typography.headlineMedium, color = PcColors.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(c.body, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            StepBody(state, cb)
        }
        Spacer(Modifier.height(12.dp))
        GradientButton(state.primaryLabel(), onClick = cb.onPrimary, modifier = Modifier.fillMaxWidth().testTag("primary"), enabled = state.primaryEnabled())
        state.secondaryLabel()?.let { TextButton(onClick = cb.onSecondary, Modifier.fillMaxWidth().testTag("secondary")) { Text(it, color = PcColors.InkSoft) } }
    }
}

@Composable
private fun ProgressBar(filled: Int) {
    Row(Modifier.fillMaxWidth().testTag("progress"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(Step.entries.size) { i ->
            val on = i < filled
            Box(
                Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (on) PcColors.Mint else PcColors.SurfaceSoft)
                    .testTag("seg_$i").semantics { stateDescription = if (on) "filled" else "empty" },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
