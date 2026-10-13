package com.rokid.context.phone.ui.shell

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootViewModel
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
}
