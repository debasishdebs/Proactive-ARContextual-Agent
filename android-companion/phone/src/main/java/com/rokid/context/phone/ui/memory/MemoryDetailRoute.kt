package com.rokid.context.phone.ui.memory

import androidx.compose.runtime.Composable
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetScopeSetting
import com.rokid.context.memory.forget.ForgetTarget
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
            // v0.9.7 slice C: both go through the Forget scope (Settings > Capture > Forget scope); the dialog passes the scope it showed.
            override suspend fun forgetFact(memoryId: String) = done(ForgetActions.forgetScopedOutcome(app, ForgetTarget.Fact(memoryId), onForgotten = onForgotten))
            override suspend fun forgetMoment(frameId: String) = done(ForgetActions.forgetScopedOutcome(app, ForgetTarget.Photo(frameId), onForgotten = onForgotten))
            override fun activeScope() = ForgetScopeSetting.get()
            private fun targetOf(kind: ForgetKind, memoryId: String, frameId: String?): ForgetTarget? = when (kind) { ForgetKind.FACT -> ForgetTarget.Fact(memoryId); ForgetKind.MOMENT -> frameId?.let { ForgetTarget.Photo(it) } }
            override suspend fun preview(kind: ForgetKind, memoryId: String, frameId: String?): ForgetPreview? {
                val target = targetOf(kind, memoryId, frameId) ?: return null
                val scope = ForgetScopeSetting.get() // read once: the counts and the delete name this scope
                val p = ForgetActions.previewScoped(app, target, scope)
                return ForgetPreview(scope, p.effective, p.photos, p.memories, p.marks, p.moments, p.scenesRemoved, p.scenesShrunk, p.planHash, p.outside)
            }
            override suspend fun forgetScoped(kind: ForgetKind, memoryId: String, frameId: String?, scope: ForgetScope, planHash: String?): Boolean {
                val target = targetOf(kind, memoryId, frameId) ?: return false
                return done(ForgetActions.forgetScopedOutcome(app, target, scope, planHash, onForgotten))
            }
            /** The typed outcome: a blocked Forget becomes the typed exception the view model names to the user (an export, a Canvas save, or a cached export that could not be deleted). */
            private fun done(o: ForgetActions.Outcome): Boolean = when (o) {
                ForgetActions.Outcome.DONE -> true
                ForgetActions.Outcome.EXPORT_IN_PROGRESS -> throw com.rokid.context.orchestration.DataOpGate.ExportInProgress()
                ForgetActions.Outcome.SAVE_IN_PROGRESS -> throw com.rokid.context.orchestration.DataOpGate.WriteInProgress()
                ForgetActions.Outcome.CACHE_CLEANUP_FAILED -> throw CacheCleanupFailed()
                ForgetActions.Outcome.CONTENT_CHANGED -> throw ContentChanged()
                ForgetActions.Outcome.FAILED -> false
            }
        }, onTextEdited = { com.rokid.context.orchestration.MemoryExport.deleteCached(app) })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.forgotten) {
        if (state.forgotten) {
            // No snackbar host on the screens behind (owned by other streams): a Toast carries "Forgotten" across the pop.
            Toast.makeText(app, state.message ?: ForgetCopy.DONE, Toast.LENGTH_SHORT).show()
            back()
        }
    }
    MemoryDetailScreen(
        state, onBack = back, onTrust = vm::trust, onStartEdit = vm::startEdit, onDraftChange = vm::updateDraft, onSaveEdit = vm::saveEdit,
        onCancelEdit = vm::cancelEdit, onOpenFrames = { date, frameId -> nav.navigate(Routes.frames(date, frameId)) },
        onForget = vm::askForget, onConfirmForget = vm::confirmForget, onDismissForget = vm::dismissForget,
    )
}
