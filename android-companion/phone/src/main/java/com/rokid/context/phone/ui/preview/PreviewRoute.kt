package com.rokid.context.phone.ui.preview

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rokid.context.BuildConfig
import com.rokid.context.phone.canvas.DeveloperModeStore
import com.rokid.context.phone.ui.actions.ProposalCard
import com.rokid.context.phone.ui.settings.diagnostics.PhotoDimEval
import com.rokid.context.phone.ui.settings.diagnostics.PhotoDimEvalFactory
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class PreviewUiState(val loading: Boolean = true, val item: Previewable? = null)

/** Loads one item by its ref through the kind's presenter, and again whenever it (or a source it cites) changes. */
class PreviewViewModel(private var ref: PreviewRef?, private val data: ActivityData, private val zone: () -> ZoneId, private val today: () -> LocalDate) : ViewModel() {
    private val _state = MutableStateFlow(PreviewUiState())
    val state: StateFlow<PreviewUiState> = _state
    /** The route re-reads on resume only after the first one (the load itself); kept here so it survives the composition being disposed under a preview opened on top. */
    var resumedOnce = false

    private var watch: kotlinx.coroutines.Job? = null

    init { observe() }

    private fun observe() {
        watch?.cancel()
        reload()
        val r = ref ?: return
        watch = viewModelScope.launch { data.changes(r).drop(1).collect { reload() } }
    }

    /** Reads the item again (back from a Forget of something it cites): the sections show what is stored now. */
    fun reload() {
        viewModelScope.launch {
            val item = ref?.let { Previewables.forRef(it.kind).load(listOf(it.id), PreviewCtx(data, zone(), today(), withDay = true)).firstOrNull() }
            _state.value = PreviewUiState(loading = false, item = item)
        }
    }

    /** After a photo was forgotten from a day's pager: the previewer stays on the photo now at its place. */
    fun showPhoto(frameId: String) { ref = PreviewRef(RefKind.FRAME, frameId); observe() }
}

@Composable
fun PreviewRoute(raw: String, deps: PhoneDeps, nav: NavController) {
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val app = LocalContext.current.applicationContext
    val ref = remember(raw) { PreviewRef.parse(raw) }
    val data = remember { activityData(deps) }
    val vm = viewModel(key = "preview_$raw") { PreviewViewModel(ref, data, deps.zone, deps::today) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from a previewer opened on top (a cited memory, its Forget): re-read, so the card and its sources are current. The first resume is the load itself.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (vm.resumedOnce) vm.reload() else vm.resumedOnce = true }
    // The memory behind the item (if any) runs Trust, Edit and Forget through the memory detail's view model: the existing ones.
    val memoryId = state.item?.sections()?.filterIsInstance<PreviewSection.TextSection>()?.firstNotNullOfOrNull { it.memoryId }.orEmpty()
    val dvm = viewModel(key = "preview_detail_$raw:$memoryId") {
        MemoryDetailViewModel(memoryId, deps.memory.timeline, deps.memory.memoryEdits, deps.zone, forget = productionMemoryForget(app), onTextEdited = { com.rokid.context.orchestration.MemoryExport.deleteCached(app) })
    }
    val detail by dvm.state.collectAsStateWithLifecycle()
    LaunchedEffect(detail.forgotten) {
        if (detail.forgotten) {
            Toast.makeText(app, detail.message ?: ForgetCopy.DONE, Toast.LENGTH_SHORT).show()
            // A photo forgotten from a day's pager: stay on the photos that remain; close only when none do.
            val paged = state.item?.sections()?.filterIsInstance<PreviewSection.ImageSection>()?.firstOrNull()?.frameIds.orEmpty()
            val next = detail.frameId?.let { photoAfterForget(paged, it) }
            if (paged.size > 1 && next != null && memoryId.isEmpty()) { dvm.forgetShown(); vm.showPhoto(next) } else back()
        }
    }
    val scope = rememberCoroutineScope()
    val devOn by DeveloperModeStore.get(app).enabled.collectAsStateWithLifecycle()
    // Debug builds with Developer mode on only: the labelling action for the photo 256-vs-768 test.
    val labels = if (PhotoDimEval.rowVisible(BuildConfig.DEBUG, devOn)) remember { PhotoDimEvalFactory.labels(app) } else null
    val host = PreviewHost(
        detail, framePath = { id -> data.frames(listOf(id)).firstOrNull()?.path },
        open = { nav.navigate(previewMoment(listOf(it)).route) }, rate = { up -> ref?.let { r -> scope.launch { deps.rateAsk(r.id, up) } } },
        morePhotos = { q -> nav.navigate(Routes.photos(q)) }, proposal = { id -> ProposalCard(id, Modifier.padding(16.dp), onEnable = { nav.navigate(Routes.SETTINGS_ACTIONS) }) },
        addToPhotoTest = labels?.let { l -> { q, id, small -> l.add(q, id, small) } },
        showTime = { day, at -> nav.navigate(Routes.day(day, at)) }, forgetPhoto = dvm::askForgetPhoto,
        trust = dvm::trust, edit = dvm::startEdit, onDraftChange = dvm::updateDraft, saveEdit = dvm::saveEdit, cancelEdit = dvm::cancelEdit,
    )
    PreviewScreen(
        state, host, title = ref?.kind?.title ?: "Preview", onBack = back,
        onForget = { t -> when (t) { is ForgetTarget.Fact -> dvm.askForget(ForgetKind.FACT); is ForgetTarget.Photo -> dvm.askForgetPhoto(t.frameId); is ForgetTarget.Mark -> dvm.askForgetObject(ForgetKind.MARK, t.labelId); else -> Unit } },
        onConfirmForget = dvm::confirmForget, onDismissForget = dvm::dismissForget, onAckForget = dvm::acknowledgeForget,
    )
}
