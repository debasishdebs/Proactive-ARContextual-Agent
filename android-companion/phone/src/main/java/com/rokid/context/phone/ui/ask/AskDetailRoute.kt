package com.rokid.context.phone.ui.ask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.phone.ui.actions.ProposalCard
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes

/** Ask detail's wiring (moved from Destinations.kt in Phase 7 Stream 0; Stream B owns it from here). */
@Composable
fun AskDetailRoute(id: String, deps: PhoneDeps, nav: NavController) {
    // Never pop the root (same rule as Destinations.kt).
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val vm = viewModel { AskDetailViewModel(id, deps.memory.asks, deps.memory.proposals, deps.memory.timeline, deps.rateAsk, deps.zone) }
    val state by vm.state.collectAsStateWithLifecycle()
    AskDetailScreen(state, onBack = back, onRate = vm::rate, onOpenMemory = { nav.navigate(Routes.memory(it)) }, proposalCard = {
        ProposalCard(id, onEnable = { nav.navigate(Routes.SETTINGS_ACTIONS) })
    })
}
