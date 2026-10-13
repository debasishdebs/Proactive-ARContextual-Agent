package com.rokid.context.phone.ui.shell

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootViewModel
import com.rokid.context.phone.ui.settings.ai.EndpointEditorScreen
import com.rokid.context.phone.ui.settings.ai.EndpointEditorViewModel
import com.rokid.context.phone.ui.settings.ai.EndpointsListScreen
import com.rokid.context.phone.ui.settings.ai.EndpointsListViewModel
import com.rokid.context.phone.ui.settings.ai.RolesScreen
import com.rokid.context.phone.ui.settings.ai.RolesViewModel
import com.rokid.context.phone.ui.settings.capture.CaptureSettingsScreen
import com.rokid.context.phone.ui.settings.capture.CaptureSettingsViewModel
import com.rokid.context.phone.ui.settings.glasses.GlassesSettingsScreen
import com.rokid.context.phone.ui.settings.glasses.GlassesSettingsViewModel

/** Settings tree routes (spec §2.1). Same rule as Destinations.kt: the only place these ViewModels are created. */
fun NavGraphBuilder.settingsDestinations(deps: PhoneDeps, nav: NavHostController, back: () -> Unit) {
    val s = deps.settings

    composable(Routes.SETTINGS) {
        val vm = viewModel { SettingsRootViewModel(deps.glasses, s.hud) }
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsRootScreen(state, live = Routes.LIVE_SETTINGS, onOpen = { nav.navigate(it) })
    }

    composable(Routes.SETTINGS_GLASSES) {
        val vm = viewModel { GlassesSettingsViewModel(deps.glasses, s.hud, s.reauthorize) }
        val state by vm.state.collectAsStateWithLifecycle()
        GlassesSettingsScreen(state, onBack = back, onVerbosity = vm::setVerbosity, onScale = vm::setScale, onPosition = vm::setPosition,
            onGesture = vm::setGesture, onReauthorize = vm::reauthorize)
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
        val vm = viewModel { EndpointEditorViewModel(id, s.ai) }
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
}
