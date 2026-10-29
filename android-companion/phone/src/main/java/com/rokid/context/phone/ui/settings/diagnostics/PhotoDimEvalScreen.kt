package com.rokid.context.phone.ui.settings.diagnostics

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import com.rokid.context.ai.local.OnnxImageEmbedder
import com.rokid.context.orchestration.PipelineRecords
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.ai.embed.LocalModelPower
import com.rokid.context.ai.local.NomicBundle
import com.rokid.context.ai.local.NomicVision
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.data.models.LocalModelStore
import com.rokid.context.model.provider.LlamaCppNativeProvider
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.work.budget.BackgroundWork
import com.rokid.context.work.budget.PhonePowerPolicy
import com.rokid.context.work.frames.FramePhotos
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the screen shows: fixed messages and numbers, never query text or ids. */
data class PhotoDimEvalUiState(
    val running: Boolean = false, val progress: String? = null, val message: String? = null, val lines: List<String> = emptyList(),
    val labels: PhotoLabels.Counts = PhotoLabels.Counts(0, 0, 0), val distractors: Int = 0,
)

class PhotoDimEvalViewModel(private val eval: PhotoDimEval, private val labels: PhotoLabels, private val bundleReady: () -> Boolean = { true }) : ViewModel() {
    private val _state = MutableStateFlow(PhotoDimEvalUiState())
    val state: StateFlow<PhotoDimEvalUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    /** Re-reads the label and distractor counts. */
    fun refresh() {
        viewModelScope.launch {
            val c = labels.counts()
            val p = try { eval.preview() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (_: Exception) { null }
            _state.value = _state.value.copy(labels = c, distractors = p?.distractors ?: 0)
        }
    }

    fun start() {
        if (job?.isActive == true) return
        if (!bundleReady()) { _state.value = _state.value.copy(message = NOT_READY); return }
        _state.value = _state.value.copy(running = true, progress = "Starting\u2026", message = null, lines = emptyList())
        job = viewModelScope.launch {
            val out = eval.run { p -> _state.value = _state.value.copy(progress = "${p.done} / ${p.total}") }
            val base = _state.value.copy(running = false, progress = null, lines = emptyList())
            _state.value = when (out) {
                is PhotoDimEval.Outcome.Done -> base.copy(message = "Done. Result saved for the controller.", lines = PhotoDimEval.lines(out))
                is PhotoDimEval.Outcome.Refused -> base.copy(message = out.message)
                is PhotoDimEval.Outcome.Blocked -> base.copy(message = out.message)
                is PhotoDimEval.Outcome.Failed -> base.copy(message = out.message)
            }
        }
        job?.invokeOnCompletion { c -> if (c != null) _state.value = _state.value.copy(running = false, progress = null, message = "Cancelled") }
    }

    fun cancel() { job?.cancel() }

    /** Deletes the labels and the result file. */
    fun clearLabels() {
        if (job?.isActive == true) return
        labels.clear()
        _state.value = _state.value.copy(message = "Labels cleared", lines = emptyList())
        refresh()
    }

    companion object { const val NOT_READY = "Download the Nomic Embed model first (Settings > AI > On-device models)." }
}

/** Production wiring: the downloaded bundle (not the bound space), photos opened in-app, the same thermal and battery gate as backfill. */
object PhotoDimEvalFactory {
    fun dir(app: Context) = File(app.filesDir, com.rokid.context.orchestration.MemoryWipe.PHOTO_EVAL_DIR)

    fun labels(app: Context) = PhotoLabels(dir(app))

    fun create(app: Context): PhotoDimEval {
        val models = LocalModelStore.modelsDir(app)
        val text = File(models, "nomic-embed-text-v1.5.Q8_0.gguf")
        val vision = File(models, NomicBundle.VISION_FILE)
        val policy = PhonePowerPolicy({ System.currentTimeMillis() }, { true }, { KeyframeGraph.thermal.status() })
        val embedder = object : PhotoDimEval.Embedder {
            // A private ONNX session (not the process-wide cache live embedding uses), closed when the run ends.
            private var onnx: OnnxImageEmbedder? = null
            override suspend fun images(jpegs: List<ByteArray>): List<FloatArray> =
                (onnx ?: OnnxImageEmbedder(vision.path, NomicVision.STORED_DIMS).also { onnx = it }).rawCls(jpegs)
            override suspend fun queries(prefixed: List<String>) = withContext(Dispatchers.Default) {
                LlamaCppNativeProvider.embed(text.path, prefixed, normalize = false)
            }
            override fun close() { onnx?.close(); onnx = null }
        }
        val loader = PhotoDimEval.PhotoLoader { ids ->
            FramePhotos.load(app, ids).photos.associate { it.frameId to Base64.decode(it.image.base64Data, Base64.NO_WRAP) }
        }
        val catalog = PhotoDimEval.FrameCatalog { PipelineRecords.allFrames(app).map { PhotoDimEval.FrameRef(it.id, it.ts) } }
        return PhotoDimEval(
            dir(app), loader, catalog, embedder,
            gate = { policy.mayRunBackground(BackgroundWork.BACKFILL) && LocalModelPower.bulkAllowed(LocalModelPower.read(app)) },
        )
    }

    fun bundleReady(app: Context): Boolean = File(LocalModelStore.modelsDir(app), NomicBundle.VISION_FILE).isFile &&
        File(LocalModelStore.modelsDir(app), "nomic-embed-text-v1.5.Q8_0.gguf").isFile
}

/** The route's gate: outside a debug build with Developer mode on the screen is never composed and the route goes back. */
@Composable
fun PhotoDimEvalGate(debug: Boolean, developerMode: Boolean, back: () -> Unit, content: @Composable () -> Unit) {
    if (PhotoDimEval.rowVisible(debug, developerMode)) content() else LaunchedEffect(Unit) { back() }
}

@Composable
fun PhotoDimEvalScreen(state: PhotoDimEvalUiState, onBack: () -> Unit, onRun: () -> Unit, onCancel: () -> Unit, onClear: () -> Unit = {}) {
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear labels?") },
            text = { Text("Deletes your photo-test queries and the last result.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }, modifier = Modifier.testTag("photo_dim_clear_confirm")) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    val l = state.labels
    val need = maxOf(PhotoDimEval.MIN_DISTRACTORS, PhotoDimEval.DISTRACTOR_FACTOR * l.photos)
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Photo search dimension test", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Debug only. Label photos in the photo previewer (Add to photo test), then run. Your labelled photos are embedded at 768 and at 256 dimensions in memory, " +
                        "hidden among up to ${PhotoDimEval.MAX_DISTRACTORS} of your other photos, and recall@3 is compared. Nothing is written to your search index. " +
                        "Cutting the image vector to 256 is not a documented Nomic property, so this is evidence from your own photos only.",
                    color = PcColors.InkSoft,
                )
                NoticeText("Queries ${l.queries} / ${PhotoDimEval.MIN_QUERIES} \u00b7 small ${l.small} / ${PhotoDimEval.MIN_SMALL} \u00b7 labelled photos ${l.photos} / ${PhotoDimEval.MIN_PHOTOS} \u00b7 other photos ${state.distractors} / $need", tag = "photo_dim_counts")
                val short = l.queries < PhotoDimEval.MIN_QUERIES || l.small < PhotoDimEval.MIN_SMALL || l.photos < PhotoDimEval.MIN_PHOTOS
                if (short) NoticeText("Add more: open a photo in the previewer and tap Add to photo test.", tag = "photo_dim_hint")
                if (state.running) PrimaryAction("Cancel", onCancel, tag = "photo_dim_cancel") else PrimaryAction("Run test", onRun, tag = "photo_dim_run")
                if (!state.running) TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("photo_dim_clear")) { Text("Clear labels") }
                state.progress?.takeIf { state.running }?.let { NoticeText(it, tag = "photo_dim_progress") }
                state.message?.let { NoticeText(it, tag = "photo_dim_message") }
            }
        }
        if (state.lines.isNotEmpty()) {
            SectionLabel("Result")
            SoftCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) { state.lines.forEachIndexed { i, line -> NoticeText(line, tag = "photo_dim_line_$i") } }
            }
        }
    }
}
