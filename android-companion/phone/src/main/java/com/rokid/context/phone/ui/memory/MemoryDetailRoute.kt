package com.rokid.context.phone.ui.memory

import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.phone.hub.HubGraph
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes

/** Memory detail's wiring (moved from Destinations.kt in Phase 7 Stream 0; Stream F owns it). Forget runs via [ForgetActions]. */
@Composable
fun MemoryDetailRoute(id: String, deps: PhoneDeps, nav: NavController) {
    // Never pop the root (same rule as Destinations.kt).
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val app = LocalContext.current.applicationContext
    val vm = viewModel {
        MemoryDetailViewModel(id, deps.memory.timeline, deps.memory.memoryEdits, deps.zone, forget = object : MemoryForget {
            // The hub refresh makes the next StateFrame drop the memory from `today`.
            private val onForgotten = { HubGraph.get(app).refresh() }
            override suspend fun forgetFact(memoryId: String) = ForgetActions.forgetFact(app, memoryId, onForgotten)
            override suspend fun forgetMoment(frameId: String) = ForgetActions.forgetMoment(app, frameId, onForgotten)
        }, onTextEdited = { com.rokid.context.orchestration.MemoryExport.deleteCached(app) })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.forgotten) {
        if (state.forgotten) {
            // No snackbar host on the screens behind (owned by other streams): a Toast carries "Forgotten" across the pop.
            Toast.makeText(app, ForgetCopy.DONE, Toast.LENGTH_SHORT).show()
            back()
        }
    }
    MemoryDetailScreen(
        state, onBack = back, onTrust = vm::trust, onStartEdit = vm::startEdit, onDraftChange = vm::updateDraft, onSaveEdit = vm::saveEdit,
        onCancelEdit = vm::cancelEdit, onOpenFrames = { date, frameId -> nav.navigate(Routes.frames(date, frameId)) },
        onForget = vm::askForget, onConfirmForget = vm::confirmForget, onDismissForget = vm::dismissForget,
    )
}
