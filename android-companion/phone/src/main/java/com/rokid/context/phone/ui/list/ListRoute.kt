package com.rokid.context.phone.ui.list

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import com.rokid.context.phone.ui.preview.PreviewMode
import com.rokid.context.phone.ui.preview.RepoActivityData
import com.rokid.context.phone.ui.preview.previewMoment
import com.rokid.context.phone.ui.shell.PhoneDeps

/** The common list of an Activity kind ([listView]); a row's tap goes through [previewMoment]. */
@Composable
fun ListViewRoute(kind: ListKind, ids: List<String>, deps: PhoneDeps, nav: NavController) {
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val app = LocalContext.current.applicationContext
    val source = remember(kind, ids) {
        RecordListSource(
            kind, ids, RepoActivityData(deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.memory.marks, deps.zone), deps.zone, deps::today,
            onPreview = { ref -> nav.navigate(previewMoment(listOf(ref), PreviewMode.OPEN).route) },
        )
    }
    ListScreen(source, onBack = back, onMessage = { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() })
}
