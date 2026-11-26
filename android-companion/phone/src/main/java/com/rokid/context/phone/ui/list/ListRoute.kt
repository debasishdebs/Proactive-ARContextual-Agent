package com.rokid.context.phone.ui.list

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionPreview
import com.rokid.context.orchestration.forget.DeveloperFlags
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.phone.ui.list.paging.SelectionForgetPort
import com.rokid.context.phone.ui.preview.PreviewMode
import com.rokid.context.phone.ui.preview.activityData
import com.rokid.context.phone.ui.preview.previewMoment
import com.rokid.context.phone.ui.shell.PhoneDeps

/** The common list of an Activity kind ([listView]); a row's tap goes through [previewMoment]. The pages live in a [ListViewModel] of this back-stack entry. */
@Composable
fun ListViewRoute(kind: ListKind, ids: List<String>, scope: ListScope?, deps: PhoneDeps, nav: NavController) {
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val app = LocalContext.current.applicationContext
    val vm = viewModel(key = "list:${kind.name}:${scope?.key}:${ids.hashCode()}") {
        ListViewModel(kind, ids, scope, activityData(deps), deps.zone, deps::today, createSavedStateHandle())
    }
    vm.openPreview = { ref -> nav.navigate(previewMoment(listOf(ref), PreviewMode.OPEN).route) }
    val breakdown = vm.breakdown?.let { b ->
        BreakdownUi(b.tree, vm.view, vm::setView, BreakdownActions(vm::toggleDay, vm::toggleHour, vm::previousPage, vm::nextPage))
    }
    // Multi-select Forget exists only while the `forgetV2` setting is ON (default ON since 26 Nov, Settings > Privacy switch): with it OFF the port is null and the list is exactly the unselectable one.
    vm.forgetPort = SelectionGate.port(app)
    ListScreen(vm.source, onBack = back, onMessage = { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }, breakdown = breakdown, selection = vm.selection, filter = if (vm.canFilter) FilterUi(vm.filter, vm::applyFilter, vm::clearFilter) else null)
}

/** The one switch of the multi-select Forget (proxy ruling P2; default ON since owner round 6b, with a Settings > Privacy switch). */
object SelectionGate {
    /** The production Forget behind selection, or null while the `forgetV2` setting is OFF. Read on every composition (a cached preference), so the switch takes effect at once. */
    fun port(context: android.content.Context): SelectionForgetPort? = if (DeveloperFlags.forgetV2(context)) ProductionSelectionForget(context.applicationContext) else null
}

private class ProductionSelectionForget(private val context: android.content.Context) : SelectionForgetPort {
    override suspend fun preview(roots: Set<Ref>): SelectionPreview? = ForgetActions.previewSelection(context, roots)
    override suspend fun forget(roots: Set<Ref>, token: String) = forget(roots, token, emptySet())
    override suspend fun forget(roots: Set<Ref>, token: String, pins: Set<String>) = ForgetActions.forgetSelectionOutcome(context, roots, token, onForgotten = ForgetActions.dataRemoved, pins = pins)
}
