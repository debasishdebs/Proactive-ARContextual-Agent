package com.rokid.context.phone.ui.shell

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rokid.context.phone.ui.settings.DiagnosticsScreen
import com.rokid.context.phone.ui.settings.DiagnosticsViewModel
import com.rokid.context.phone.ui.settings.LogsScreen
import com.rokid.context.phone.ui.settings.LogsViewModel
import com.rokid.context.phone.ui.settings.PersonaScreen
import com.rokid.context.phone.ui.settings.PersonaViewModel
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootViewModel
import com.rokid.context.phone.ui.settings.ai.EndpointEditorScreen
import com.rokid.context.phone.ui.settings.ai.EndpointEditorViewModel
import com.rokid.context.phone.ui.settings.ai.EndpointsListScreen
import com.rokid.context.phone.ui.settings.ai.EndpointsListViewModel
import com.rokid.context.phone.ui.settings.ai.RolesScreen
import com.rokid.context.phone.ui.settings.ai.RolesViewModel
import com.rokid.context.phone.ui.settings.capture.CaptureSettingsScreen
import com.rokid.context.phone.ui.settings.flows.AgentFlowSummaries
import com.rokid.context.phone.ui.settings.flows.FlowViewModel
import com.rokid.context.phone.ui.settings.flows.FlowViewRoute
import com.rokid.context.phone.ui.settings.flows.FlowsListRoute
import com.rokid.context.phone.ui.settings.connectors.ActionsRoute
import com.rokid.context.phone.ui.settings.connectors.ShoppingRoute
import com.rokid.context.phone.ui.settings.connectors.SourcesRoute
import com.rokid.context.phone.ui.settings.capture.CaptureSettingsViewModel
import com.rokid.context.phone.ui.settings.glasses.GlassesSettingsScreen
import com.rokid.context.phone.ui.settings.glasses.GlassesSettingsViewModel
import com.rokid.context.phone.ui.settings.inputs.PhoneInputsScreen
import com.rokid.context.phone.ui.settings.inputs.PhoneInputsViewModel
import com.rokid.context.phone.ui.settings.memory.MemorySettingsScreen
import com.rokid.context.phone.ui.settings.memory.MemorySettingsViewModel
import com.rokid.context.phone.ui.settings.memory.ReviewQueueScreen
import com.rokid.context.phone.ui.settings.memory.ReviewQueueViewModel
import com.rokid.context.phone.ui.settings.privacy.PrivacyRoute
import com.rokid.context.phone.ui.settings.prompts.PromptEditorScreen
import com.rokid.context.phone.ui.settings.prompts.PromptEditorViewModel
import com.rokid.context.phone.ui.settings.prompts.PromptHistoryScreen
import com.rokid.context.phone.ui.settings.prompts.PromptHistoryViewModel
import com.rokid.context.phone.ui.settings.prompts.PromptsListScreen
import com.rokid.context.phone.ui.settings.prompts.PromptsListViewModel
import com.rokid.context.phone.ui.settings.retrieval.RetrievalSettingsScreen
import com.rokid.context.phone.ui.settings.retrieval.RetrievalViewModel

/** Settings tree routes (spec §2.1). Same rule as Destinations.kt: the only place these ViewModels are created. */
fun NavGraphBuilder.settingsDestinations(deps: PhoneDeps, nav: NavHostController, back: () -> Unit) {
    val s = deps.settings

    composable(Routes.SETTINGS) {
        val vm = viewModel { SettingsRootViewModel(deps.glasses, s.hud, s.voice.capabilities) }
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsRootScreen(state, live = Routes.LIVE_SETTINGS, onOpen = { nav.navigate(it) })
    }

    composable(Routes.SETTINGS_GLASSES) {
        val vm = viewModel { GlassesSettingsViewModel(deps.glasses, s.hud, voice = s.voice.capabilities, reauthorizeAction = s.reauthorize) }
        val state by vm.state.collectAsStateWithLifecycle()
        GlassesSettingsScreen(state, onBack = back, onVerbosity = vm::setVerbosity, onScale = vm::setScale, onPosition = vm::setPosition,
            onGesture = vm::setGesture, onReauthorize = vm::reauthorize)
    }

    composable(Routes.SETTINGS_PHONE_INPUTS) {
        val vm = viewModel { PhoneInputsViewModel(s.voice, s.capture) }
        val state by vm.state.collectAsStateWithLifecycle()
        val activity = LocalContext.current.findActivity()
        val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // After a denial, no rationale means "don't ask again" (or a dialog the system no longer shows).
            val canAskAgain = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: true
            vm.onMicResult(granted, canAskAgain)
        }
        PhoneInputsScreen(
            state, onBack = back, onAllowMic = { mic.launch(Manifest.permission.RECORD_AUDIO) }, onDownload = vm::download,
            onOpenSettings = vm::openSettings, onRecheck = vm::recheck, onLanguage = vm::setLanguage, onPhoneMic = vm::setPhoneMic,
            onOpenAppSettings = s.openAppSettings, onResume = vm::onResume,
            onCaptureNow = vm::captureNow, onPictureTaken = vm::onPictureTaken,
        )
    }

    composable(Routes.SETTINGS_CAPTURE) {
        val vm = viewModel { CaptureSettingsViewModel(s.hud) }
        val state by vm.state.collectAsStateWithLifecycle()
        CaptureSettingsScreen(state, onBack = back, onInterval = vm::setInterval, onBurst = vm::setBurst)
    }

    composable(Routes.SETTINGS_ENDPOINTS) {
        val vm = viewModel { EndpointsListViewModel(s.ai) }
        val state by vm.state.collectAsStateWithLifecycle()
        EndpointsListScreen(state, onBack = back, onOpen = { nav.navigate(Routes.endpoint(it)) })
    }

    composable(Routes.SETTINGS_ENDPOINT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { EndpointEditorViewModel(id, s.ai, onEmbedChanged = s.onEmbedBindingChanged) }
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(state.deleted) { if (state.deleted) back() }
        EndpointEditorScreen(
            state, onBack = back, onName = vm::onName, onKind = vm::onKind, onBaseUrl = vm::onBaseUrl, onApiVersion = vm::onApiVersion, onModels = vm::onModels,
            onCapability = vm::onCapability, onKey = vm::onKey, onClearKey = vm::clearKey, onTestModel = vm::onTestModel, onSave = vm::save, onTest = vm::test, onDelete = vm::delete,
        )
    }

    composable(Routes.SETTINGS_ROLES) {
        val vm = viewModel { RolesViewModel(s.ai, s.onEmbedBindingChanged) }
        val state by vm.state.collectAsStateWithLifecycle()
        RolesScreen(state, onBack = back, onEndpoint = vm::setEndpoint, onModel = vm::setModel, onFallback = vm::setFallback,
            onTemperature = vm::setTemperature, onMaxTokens = vm::setMaxTokens, onClear = { vm.clear(it) })
    }

    composable(Routes.SETTINGS_FLOWS) {
        FlowsListRoute(AgentFlowSummaries, models = { s.ai.bindings.get(it)?.model }, onBack = back, navigate = { nav.navigate(it) })
    }

    composable(Routes.SETTINGS_FLOWS_VIEW, arguments = listOf(navArgument("ids") { type = NavType.StringType; defaultValue = "" })) { entry ->
        val ids = entry.arguments?.getString("ids").orEmpty().split(",")
        val vm = viewModel { FlowViewModel(ids, s.ai, s.retrieval) }
        // The sheet's tabs build these as their own destinations do, so a save goes through the same write path and validation.
        FlowViewRoute(
            vm,
            roles = { viewModel { RolesViewModel(s.ai, s.onEmbedBindingChanged) } },
            retrieval = { viewModel { RetrievalViewModel(s.retrieval, s.ai.retriever, s.spaces, s.ai, s.rebuildIndex) } },
            onBack = back, navigate = { nav.navigate(it) },
        )
    }

    composable(Routes.SETTINGS_PROMPTS) {
        val vm = viewModel { PromptsListViewModel(s.ai) }
        val state by vm.state.collectAsStateWithLifecycle()
        PromptsListScreen(state, onBack = back, onKeepVersions = vm::setKeepVersions, onOpen = { nav.navigate(Routes.prompt(it)) })
    }

    composable(Routes.SETTINGS_PROMPT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { PromptEditorViewModel(id, s.ai, s.testInputs) }
        val state by vm.state.collectAsStateWithLifecycle()
        PromptEditorScreen(
            state, onBack = back, onTemplate = vm::onTemplate, onSchema = vm::onSchema, onResetSchema = vm::resetSchema, onInputKind = vm::onInputKind,
            onInputText = vm::onInputText, onFrame = vm::onFrame, onEndpoint = vm::onEndpoint, onModel = vm::onModel, onRun = vm::run, onSave = vm::save,
            onDiscard = vm::discard, onToggleWire = vm::toggleWire, onOpenHistory = { nav.navigate(Routes.promptHistory(id)) },
        )
    }

    composable(Routes.SETTINGS_PROMPT_HISTORY, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { PromptHistoryViewModel(id, s.ai) }
        val state by vm.state.collectAsStateWithLifecycle()
        PromptHistoryScreen(state, onBack = back, onSelect = vm::select, onRestore = vm::restore)
    }

    composable(Routes.SETTINGS_RETRIEVAL) {
        val vm = viewModel { RetrievalViewModel(s.retrieval, s.ai.retriever, s.spaces, s.ai, s.rebuildIndex) }
        val state by vm.state.collectAsStateWithLifecycle()
        RetrievalSettingsScreen(
            state, onBack = back, onMode = vm::setMode, onFusion = vm::setFusion, onWeight = vm::setWeight, onRrfK = vm::setRrfK, onTopK = vm::setTopK,
            onRecency = vm::setRecency, onHalfLife = vm::setHalfLife, onMinConfidence = vm::setMinConfidence, onQuery = vm::onQuery, onTryIt = vm::tryIt,
            onRebuild = vm::rebuild, onOpenRoles = { nav.navigate(Routes.SETTINGS_ROLES) },
        )
    }

    composable(Routes.SETTINGS_MEMORY) {
        val vm = viewModel {
            MemorySettingsViewModel({ s.memorySettings }, s.encryption, s.usedBytes, s.wipe, s.export, s.share, deps.memory.memoryEdits.needsReviewCount())
        }
        val state by vm.state.collectAsStateWithLifecycle()
        MemorySettingsScreen(
            state, onBack = back, onShort = vm::setShort, onMedium = vm::setMedium, onLong = vm::setLong, onQuota = vm::setQuota,
            onEncryption = { vm.setEncryption(it) }, onExport = vm::export, onAskDeleteAll = vm::askDeleteAll, onConfirmDeleteAll = vm::confirmDeleteAll,
            onCancelDeleteAll = vm::cancelDeleteAll, onOpenReview = { nav.navigate(Routes.SETTINGS_MEMORY_REVIEW) },
        )
    }

    composable(Routes.SETTINGS_MEMORY_REVIEW) {
        val vm = viewModel { ReviewQueueViewModel(deps.memory.memoryEdits, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        ReviewQueueScreen(state, onBack = back, onKeep = { vm.keep(it) })
    }

    composable(Routes.SETTINGS_DIAGNOSTICS) {
        val vm = viewModel { DiagnosticsViewModel(deps.diagnostics, deps.pipeline, s.exportDiagnostics, { f -> s.share(f) }, { s.hud.update { it } },
            glassesConnected = { deps.glasses.status.value.link == com.rokid.context.core.model.LinkState.CONNECTED }) }
        val state by vm.state.collectAsStateWithLifecycle()
        DiagnosticsScreen(
            state, onBack = back, onOpenQueues = { nav.navigate(Routes.DIAGNOSTICS_QUEUES) }, onOpenTracker = { nav.navigate(Routes.DIAGNOSTICS_TRACKER) },
            onSummarizeNow = vm::summarizeNow, onCaptureNow = vm::captureNow, onReprocess = vm::reprocess,
            onOpenLogs = { nav.navigate(Routes.DIAGNOSTICS_LOGS) }, onExport = vm::exportDiagnostics, onPushSettings = vm::pushSettings,
        )
    }

    composable(Routes.DIAGNOSTICS_LOGS) {
        val vm = viewModel { LogsViewModel(s.logs) }
        val state by vm.state.collectAsStateWithLifecycle()
        LogsScreen(state, onBack = back, onRefresh = vm::refresh)
    }

    composable(Routes.SETTINGS_PRIVACY) { PrivacyRoute(s, back) }
    composable(Routes.SETTINGS_SOURCES) {
        val activity = LocalContext.current.findActivity()
        SourcesRoute(s, back) { p -> activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, p) } ?: true }
    }
    composable(Routes.SETTINGS_ACTIONS) { ActionsRoute(s, back, openShopping = { nav.navigate(Routes.SETTINGS_SHOPPING) }) }
    composable(Routes.SETTINGS_SHOPPING) { ShoppingRoute(s, back) }

    composable(Routes.SETTINGS_PERSONA) {
        val vm = viewModel { PersonaViewModel(s.ai.persona) }
        val state by vm.state.collectAsStateWithLifecycle()
        PersonaScreen(state, onBack = back, onText = vm::onText, onLanguage = vm::onLanguage, onSave = vm::save)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
