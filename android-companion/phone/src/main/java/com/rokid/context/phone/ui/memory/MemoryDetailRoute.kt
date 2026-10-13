package com.rokid.context.phone.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes

/** Memory detail's wiring (moved from Destinations.kt in Phase 7 Stream 0; Stream F owns it from here). */
@Composable
fun MemoryDetailRoute(id: String, deps: PhoneDeps, nav: NavController) {
    // Never pop the root (same rule as Destinations.kt).
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val vm = viewModel { MemoryDetailViewModel(id, deps.memory.timeline, deps.memory.memoryEdits, deps.zone) }
    val state by vm.state.collectAsStateWithLifecycle()
    MemoryDetailScreen(
        state, onBack = back, onTrust = vm::trust, onStartEdit = vm::startEdit, onDraftChange = vm::updateDraft, onSaveEdit = vm::saveEdit,
        onCancelEdit = vm::cancelEdit, onOpenFrames = { date, frameId -> nav.navigate(Routes.frames(date, frameId)) },
    )
}
