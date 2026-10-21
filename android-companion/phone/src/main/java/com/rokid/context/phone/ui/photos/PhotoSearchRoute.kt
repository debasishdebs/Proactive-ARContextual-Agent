package com.rokid.context.phone.ui.photos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes

/** Wiring of "Find a photo" (wave 3). [q] non-blank: searched once on open. */
@Composable
fun PhotoSearchRoute(q: String?, deps: PhoneDeps, nav: NavController) {
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val d = deps.photoSearch
    val vm = viewModel { PhotoSearchViewModel(q, d.search, d.reenrich, d.describePhotos, d.describeScene, d.framePaths, deps.zone, deps::today, d.changes) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from a Forget on the memory screen: re-read what is on screen (S11).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    PhotoSearchScreen(
        state, onBack = back, onQueryChange = vm::onQueryChange, onSubmit = vm::submit, onDescribe = vm::describe, onDescribeScene = vm::describeScene,
        onOpenFrame = { date, id -> nav.navigate(Routes.frames(java.time.LocalDate.parse(date), id)) }, onOpenMemory = { nav.navigate(Routes.memory(it)) },
    )
}
