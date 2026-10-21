package com.rokid.context.phone.ui.shell

import androidx.compose.runtime.getValue
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
import com.rokid.context.phone.ui.ask.AskDetailRoute
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.frames.FrameViewerScreen
import com.rokid.context.phone.ui.frames.FrameViewerViewModel
import com.rokid.context.phone.ui.home.HomeRoute
import com.rokid.context.phone.ui.memory.MemoryDetailRoute
import com.rokid.context.phone.ui.nudge.NudgeDetailRoute
import com.rokid.context.phone.ui.onboarding.OnboardingRoute
import com.rokid.context.phone.ui.photos.PhotoSearchRoute
import com.rokid.context.phone.ui.settings.QueuesScreen
import com.rokid.context.phone.ui.settings.QueuesViewModel
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
    val bannerNav: (BannerAction, (BannerAction) -> Unit) -> Unit = { a, apply ->
        when (a) {
            BannerAction.FIX_AI -> nav.navigate(Routes.SETTINGS_ENDPOINTS)
            BannerAction.REBUILD_INDEX -> deps.settings.rebuildIndex()
            else -> apply(a)
        }
    }

    composable(Routes.ONBOARDING) {
        OnboardingRoute(deps) {
            deps.onboarding.markComplete()
            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true }; launchSingleTop = true }
        }
    }

    composable(Routes.HOME) { HomeRoute(deps, nav, bannerNav) }

    composable(Routes.ASK, arguments = idArg) { entry -> AskDetailRoute(entry.arguments?.getString("id").orEmpty(), deps, nav) }

    composable(Routes.NUDGE, arguments = idArg) { entry -> NudgeDetailRoute(entry.arguments?.getString("id").orEmpty(), deps, nav) }

    composable(Routes.ACTIVITY, arguments = listOf(navArgument("period") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        val period = Period.parse(entry.arguments?.getString("period"))
        val vm = viewModel { ActivityViewModel(period, deps.glasses, deps.pipeline, deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.clock, deps.storagePct, deps.zone, index = deps.index, sort = deps.activitySort) }
        val state by vm.state.collectAsStateWithLifecycle()
        ActivityScreen(
            state, onSelectPeriod = vm::selectPeriod, onSelectSort = vm::selectSort, onTogglePause = vm::togglePause, onBannerAction = { a -> bannerNav(a, vm::onBannerAction) },
            onOpenMemory = { nav.navigate(Routes.memory(it)) }, onOpenFrames = { nav.navigate(Routes.frames(it)) }, onOpenDay = { nav.navigate(Routes.day(it)) },
            onSearchPhotos = { nav.navigate(Routes.photos()) },
        )
    }

    composable(Routes.DAY, arguments = listOf(dateArg)) { entry ->
        val date = entry.arguments?.getString("date")
        // Same clock as Activity, so the day regroups when the zone changes (Task 7 carry).
        val vm = viewModel { DayViewModel(date, deps.memory.timeline, deps.zone, deps::today, deps.clock, deps.activitySort.order) }
        val state by vm.state.collectAsStateWithLifecycle()
        DayScreen(state, onBack = back, onOpenMemory = { nav.navigate(Routes.memory(it)) }, onOpenFrames = { nav.navigate(Routes.frames(it)) })
    }

    composable(Routes.PHOTOS, arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        PhotoSearchRoute(entry.arguments?.getString("q"), deps, nav)
    }

    composable(Routes.MEMORY, arguments = idArg) { entry -> MemoryDetailRoute(entry.arguments?.getString("id").orEmpty(), deps, nav) }

    composable(Routes.FRAMES, arguments = listOf(dateArg, navArgument("start") { type = NavType.StringType; nullable = true; defaultValue = null })) { entry ->
        val date = entry.arguments?.getString("date")
        val start = entry.arguments?.getString("start")
        val vm = viewModel { FrameViewerViewModel(date, start, deps.memory.timeline, deps.zone, deps::today) }
        val state by vm.state.collectAsStateWithLifecycle()
        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
        val devOn by com.rokid.context.phone.canvas.DeveloperModeStore.get(app).enabled.collectAsStateWithLifecycle()
        // Debug builds with Developer mode on only: the labelling action for the photo 256-vs-768 test.
        val labels = if (com.rokid.context.phone.ui.settings.diagnostics.PhotoDimEval.rowVisible(com.rokid.context.BuildConfig.DEBUG, devOn)) {
            androidx.compose.runtime.remember { com.rokid.context.phone.ui.settings.diagnostics.PhotoDimEvalFactory.labels(app) }
        } else null
        FrameViewerScreen(state, onBack = back, onAddToPhotoTest = labels?.let { l -> { q, id, small -> l.add(q, id, small) } })
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

    settingsDestinations(deps, nav, back)
}
