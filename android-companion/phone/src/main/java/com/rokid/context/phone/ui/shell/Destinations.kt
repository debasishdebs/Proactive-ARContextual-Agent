package com.rokid.context.phone.ui.shell

import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rokid.context.phone.ui.activity.ActivityScreen
import com.rokid.context.phone.ui.activity.ActivityViewModel
import com.rokid.context.phone.ui.activity.DayScreen
import com.rokid.context.phone.ui.activity.DayViewModel
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.ask.AskDetailScreen
import com.rokid.context.phone.ui.ask.AskDetailViewModel
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.frames.FrameViewerScreen
import com.rokid.context.phone.ui.frames.FrameViewerViewModel
import com.rokid.context.phone.ui.home.HomeScreen
import com.rokid.context.phone.ui.home.HomeViewModel
import com.rokid.context.phone.ui.memory.MemoryDetailScreen
import com.rokid.context.phone.ui.memory.MemoryDetailViewModel
import com.rokid.context.phone.ui.onboarding.WelcomeScreen
import com.rokid.context.phone.ui.settings.DiagnosticsScreen
import com.rokid.context.phone.ui.settings.DiagnosticsViewModel
import com.rokid.context.phone.ui.settings.LegacyConfigScreen
import com.rokid.context.phone.ui.settings.LegacyConfigViewModel
import com.rokid.context.phone.ui.settings.QueuesScreen
import com.rokid.context.phone.ui.settings.QueuesViewModel
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootViewModel
import com.rokid.context.phone.ui.settings.TrackerScreen
import com.rokid.context.phone.ui.settings.TrackerViewModel

private val idArg = listOf(navArgument("id") { type = NavType.StringType })
private val dateArg = navArgument("date") { type = NavType.StringType }

/**
 * The only place ViewModels are created and wired to their stateless screens (R-20). Each `viewModel {}` runs inside
 * its `composable` and is therefore scoped to that back-stack entry: popping the entry clears the ViewModel (e.g. an
 * in-flight Home ask is cancelled when Home leaves the back stack).
 */
fun NavGraphBuilder.phoneDestinations(deps: PhoneDeps, nav: NavHostController) {
    // Never pop the root: a second quick Back tap (or Back on a restored single-entry stack) would leave an empty NavHost.
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val bannerNav: (BannerAction, (BannerAction) -> Unit) -> Unit = { a, apply -> if (a == BannerAction.FIX_AI) nav.navigate(Routes.SETTINGS_ENDPOINTS) else apply(a) }

    composable(Routes.ONBOARDING) {
        WelcomeScreen(onGetStarted = {
            deps.onboarding.markComplete()
            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true }; launchSingleTop = true }
        })
    }

    composable(Routes.HOME) {
        val vm = viewModel { HomeViewModel(deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.askService, deps.clock, deps.storagePct, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        HomeScreen(
            state, onQuestionChange = vm::onQuestionChange, onAsk = { vm.ask(it) }, onOpenAsk = { nav.navigate(Routes.ask(it)) },
            onTogglePause = vm::togglePause, onBannerAction = { a -> bannerNav(a, vm::onBannerAction) },
        )
    }

    composable(Routes.ASK, arguments = idArg) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { AskDetailViewModel(id, deps.memory.asks, deps.memory.proposals, deps.memory.timeline, deps.rateAsk, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        AskDetailScreen(state, onBack = back, onRate = vm::rate, onOpenMemory = { nav.navigate(Routes.memory(it)) })
    }

    composable(Routes.ACTIVITY, arguments = listOf(navArgument("period") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        val period = Period.parse(entry.arguments?.getString("period"))
        val vm = viewModel { ActivityViewModel(period, deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.clock, deps.storagePct, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        ActivityScreen(
            state, onSelectPeriod = vm::selectPeriod, onTogglePause = vm::togglePause, onBannerAction = { a -> bannerNav(a, vm::onBannerAction) },
            onOpenMemory = { nav.navigate(Routes.memory(it)) }, onOpenFrames = { nav.navigate(Routes.frames(it)) }, onOpenDay = { nav.navigate(Routes.day(it)) },
        )
    }

    composable(Routes.DAY, arguments = listOf(dateArg)) { entry ->
        val date = entry.arguments?.getString("date")
        // Same clock as Activity, so the day regroups when the zone changes (Task 7 carry).
        val vm = viewModel { DayViewModel(date, deps.memory.timeline, deps.zone, deps::today, deps.clock) }
        val state by vm.state.collectAsStateWithLifecycle()
        DayScreen(state, onBack = back, onOpenMemory = { nav.navigate(Routes.memory(it)) }, onOpenFrames = { nav.navigate(Routes.frames(it)) })
    }

    composable(Routes.MEMORY, arguments = idArg) { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val vm = viewModel { MemoryDetailViewModel(id, deps.memory.timeline, deps.memory.memoryEdits, deps.zone) }
        val state by vm.state.collectAsStateWithLifecycle()
        MemoryDetailScreen(
            state, onBack = back, onTrust = vm::trust, onStartEdit = vm::startEdit, onDraftChange = vm::updateDraft, onSaveEdit = vm::saveEdit,
            onCancelEdit = vm::cancelEdit, onOpenFrames = { date, frameId -> nav.navigate(Routes.frames(date, frameId)) },
        )
    }

    composable(Routes.FRAMES, arguments = listOf(dateArg, navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        val date = entry.arguments?.getString("date")
        val start = entry.arguments?.getString("start")
        val vm = viewModel { FrameViewerViewModel(date, start, deps.memory.timeline, deps.zone, deps::today) }
        val state by vm.state.collectAsStateWithLifecycle()
        FrameViewerScreen(state, onBack = back)
    }

    composable(Routes.SETTINGS) {
        val vm = viewModel { SettingsRootViewModel(deps.glasses) }
        val state by vm.state.collectAsStateWithLifecycle()
        SettingsRootScreen(state, onOpenEndpoints = { nav.navigate(Routes.SETTINGS_ENDPOINTS) }, onOpenDiagnostics = { nav.navigate(Routes.SETTINGS_DIAGNOSTICS) })
    }

    composable(Routes.SETTINGS_ENDPOINTS) {
        val app = LocalContext.current.applicationContext
        val vm = viewModel { LegacyConfigViewModel(app, deps.pipeline) }
        val state by vm.state.collectAsStateWithLifecycle()
        LegacyConfigScreen(
            state, onBack = back, onTogglePipeline = vm::togglePipeline, onToggleLocalMode = vm::toggleLocalMode, onToggleEncryption = vm::toggleEncryption,
            onCycleMemoryWindow = vm::cycleMemoryWindow, onSelectProvider = vm::selectProvider, onUpdateEndpoint = vm::updateEndpoint,
            onUpdateApiKey = vm::updateApiKey, onUpdateDeploymentOrModel = vm::updateModel, onUpdateNativePath = vm::updateNativePath,
            onSelectGlassesSilencyMode = vm::selectGlassesSilencyMode, onTestConnectivity = vm::testConnectivity, onClearApiKey = vm::clearApiKey,
        )
    }

    composable(Routes.SETTINGS_DIAGNOSTICS) {
        val vm = viewModel { DiagnosticsViewModel(deps.diagnostics, deps.pipeline) }
        val state by vm.state.collectAsStateWithLifecycle()
        DiagnosticsScreen(
            state, onBack = back, onOpenQueues = { nav.navigate(Routes.DIAGNOSTICS_QUEUES) }, onOpenTracker = { nav.navigate(Routes.DIAGNOSTICS_TRACKER) },
            onSummarizeNow = vm::summarizeNow, onCaptureNow = vm::captureNow, onReprocess = vm::reprocess,
        )
    }

    composable(Routes.DIAGNOSTICS_QUEUES) {
        val vm = viewModel { QueuesViewModel(deps.queues) }
        val state by vm.state.collectAsStateWithLifecycle()
        QueuesScreen(
            state, onBack = back, onRefresh = vm::refresh, onExplicitWriteChange = vm::onExplicitWriteChange, onReadQueryChange = vm::onReadQueryChange,
            onQueueExplicitWrite = vm::queueExplicitWrite, onQueueReadContext = vm::queueReadContext, onDrainNow = vm::drainNow,
        )
    }

    composable(Routes.DIAGNOSTICS_TRACKER) {
        val vm = viewModel { TrackerViewModel(deps.memory.timeline, deps.zone) }
        val items by vm.state.collectAsStateWithLifecycle()
        TrackerScreen(items, onBack = back)
    }
}
