package com.rokid.context.phone.ui.preview

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.memory.ForgetCopy
import com.rokid.context.phone.ui.memory.ForgetKind
import com.rokid.context.phone.ui.memory.MemoryDetailViewModel
import com.rokid.context.phone.ui.memory.productionMemoryForget
import com.rokid.context.phone.ui.shell.PhoneDeps
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class PreviewUiState(val loading: Boolean = true, val item: Previewable? = null)

/** Loads one item by its ref, once, through the kind's presenter. */
class PreviewViewModel(private val ref: PreviewRef?, private val data: ActivityData, private val zone: () -> ZoneId, private val today: () -> LocalDate) : ViewModel() {
    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state

    init {
        viewModelScope.launch {
            val item = ref?.let { Previewables.forRef(it.kind).load(listOf(it.id), PreviewCtx(data, zone(), today(), withDay = true)).firstOrNull() }
            _state.value = PreviewUiState(loading = false, item = item)
        }
    }
}

@Composable
fun PreviewRoute(raw: String, deps: PhoneDeps, nav: NavController) {
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val app = LocalContext.current.applicationContext
    val ref = remember(raw) { PreviewRef.parse(raw) }
    val data = remember { RepoActivityData(deps.memory.timeline, deps.memory.asks, deps.memory.nudges, deps.memory.marks, deps.zone) }
    val vm = viewModel(key = "preview_$raw") { PreviewViewModel(ref, data, deps.zone, deps::today) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The memory behind the item (if any) runs Trust, Edit and Forget through the memory detail's view model: the existing ones.
    val memoryId = state.item?.sections()?.filterIsInstance<PreviewSection.TextSection>()?.firstNotNullOfOrNull { it.memoryId }.orEmpty()
    val dvm = viewModel(key = "preview_detail_$raw:$memoryId") {
        MemoryDetailViewModel(memoryId, deps.memory.timeline, deps.memory.memoryEdits, deps.zone, forget = productionMemoryForget(app), onTextEdited = { com.rokid.context.orchestration.MemoryExport.deleteCached(app) })
    }
    val detail by dvm.state.collectAsStateWithLifecycle()
    LaunchedEffect(detail.forgotten) {
        if (detail.forgotten) { Toast.makeText(app, detail.message ?: ForgetCopy.DONE, Toast.LENGTH_SHORT).show(); back() }
    }
    val host = PreviewHost(
        detail, framePath = { id -> data.frames(listOf(id)).firstOrNull()?.path },
        showTime = { nav.navigate(Routes.day(it)) }, forgetPhoto = dvm::askForgetPhoto,
        trust = dvm::trust, edit = dvm::startEdit, onDraftChange = dvm::updateDraft, saveEdit = dvm::saveEdit, cancelEdit = dvm::cancelEdit,
    )
    PreviewScreen(
        state, host, onBack = back,
        onForget = { t -> when (t) { is ForgetTarget.Fact -> dvm.askForget(ForgetKind.FACT); is ForgetTarget.Photo -> dvm.askForgetPhoto(t.frameId); else -> Unit } },
        onConfirmForget = dvm::confirmForget, onDismissForget = dvm::dismissForget, onAckForget = dvm::acknowledgeForget,
    )
}
