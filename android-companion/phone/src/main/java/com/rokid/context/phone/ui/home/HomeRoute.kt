package com.rokid.context.phone.ui.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes

/** Home's wiring (moved from Destinations.kt in Phase 7 Stream 0; Stream C owns it from here). Phase 7 C4: a nudge card opens its detail. */
@Composable
fun HomeRoute(deps: PhoneDeps, nav: NavController, onBanner: (BannerAction, (BannerAction) -> Unit) -> Unit) {
    val vm = viewModel { HomeViewModel(deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.askService, deps.clock, deps.storagePct, deps.zone, index = deps.index, voice = deps.voice) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused with no rationale left = "don't ask again": Home then points at the app's settings.
        if (!granted) vm.onMicDenied(permanent = activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: false)
        deps.onMicGranted() // re-check the gate either way
    }
    HomeScreen(
        state, onQuestionChange = vm::onQuestionChange, onAsk = { vm.ask(it) }, onOpenAsk = { nav.navigate(Routes.ask(it)) },
        onTogglePause = vm::togglePause, onBannerAction = { a -> onBanner(a, vm::onBannerAction) },
        onMic = vm::onMic, onRequestMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenAppSettings = { // the activity resume on return re-checks the gate (HubActivityHook)
            runCatching { activity?.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))) }
        },
        onLeave = vm::onLeave,
        onOpenNudge = { nav.navigate(Routes.nudge(it)) },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
