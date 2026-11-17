package com.rokid.context.phone.ui.list

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
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
    ListScreen(vm.source, onBack = back, onMessage = { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() })
}
