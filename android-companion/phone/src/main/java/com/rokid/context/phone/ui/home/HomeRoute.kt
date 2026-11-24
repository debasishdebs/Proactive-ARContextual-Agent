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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.preview.PreviewRef
import com.rokid.context.phone.ui.preview.RefKind
import com.rokid.context.phone.ui.preview.previewMoment

/** Home's wiring (moved from Destinations.kt in Phase 7 Stream 0; Stream C owns it from here). Phase 7 C4: a nudge card opens its detail. */
@Composable
fun HomeRoute(deps: PhoneDeps, nav: NavController, onBanner: (BannerAction, (BannerAction) -> Unit) -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val vm = viewModel { HomeViewModel(deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.askService, deps.clock, deps.storagePct, deps.zone, index = deps.index, voice = deps.voice, batterySaver = deps.batterySaver, limitedMode = deps.limitedMode, utterance = deps.utterance, askAgainOf = deps.askAgain, suggestions = deps.suggestionFeed, placeCards = com.rokid.context.phone.place.AndroidPlaceCardHomeSource(appContext, zone = deps.zone)) }
    androidx.lifecycle.compose.LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Refused with no rationale left = "don't ask again": Home then points at the app's settings.
        if (!granted) vm.onMicDenied(permanent = activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: false)
        // Re-check the gate either way, but it probes: only while RESUMED (Phase 6 F2). A result that lands while only
        // STARTED is re-checked by the activity resume that follows (HubActivityHook -> VoiceGraph.onForeground).
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) deps.onMicGranted()
    }
    HomeScreen(
        state, onQuestionChange = vm::onQuestionChange, onAsk = { vm.ask(it) }, onOpenAsk = { nav.navigate(previewMoment(listOf(PreviewRef.of(RefKind.ASK, it))).route) },
        onTogglePause = vm::togglePause, onBannerAction = { a -> onBanner(a, vm::onBannerAction) },
        onMic = vm::onMic, onRequestMic = { mic.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenAppSettings = { // the activity resume on return re-checks the gate (HubActivityHook)
            runCatching { activity?.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))) }
        },
        onLeave = vm::onLeave,
        onAskAgain = vm::askAgain,
        onOpenNudge = { nav.navigate(previewMoment(listOf(PreviewRef.of(RefKind.NUDGE, it))).route) },
        onDismissLimited = vm::dismissLimited,
        onSuggestion = SuggestionActionsUi(vm::suggestionDismiss, vm::suggestionSnooze, vm::suggestionNever, vm::suggestionThumb),
        onOpenPlaceCards = { nav.navigate(com.rokid.context.phone.ui.shell.Routes.SETTINGS_PLACE_GUIDE) },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
