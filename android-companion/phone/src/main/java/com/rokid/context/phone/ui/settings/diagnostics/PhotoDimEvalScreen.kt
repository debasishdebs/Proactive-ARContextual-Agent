package com.rokid.context.phone.ui.settings.diagnostics

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
data class PhotoDimEvalUiState(val running: Boolean = false, val progress: String? = null, val message: String? = null, val lines: List<String> = emptyList())

class PhotoDimEvalViewModel(private val eval: PhotoDimEval, private val bundleReady: () -> Boolean = { true }) : ViewModel() {
    private val _state = MutableStateFlow(PhotoDimEvalUiState())
    val state: StateFlow<PhotoDimEvalUiState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        if (!bundleReady()) { _state.value = PhotoDimEvalUiState(message = NOT_READY); return }
        _state.value = PhotoDimEvalUiState(running = true, progress = "Starting…")
        job = viewModelScope.launch {
            val out = eval.run { p -> _state.value = _state.value.copy(progress = "${p.done} / ${p.total}") }
            _state.value = when (out) {
                is PhotoDimEval.Outcome.Done -> PhotoDimEvalUiState(message = "Done. Result saved for the controller.", lines = PhotoDimEval.lines(out))
                is PhotoDimEval.Outcome.Refused -> PhotoDimEvalUiState(message = out.message)
                is PhotoDimEval.Outcome.Blocked -> PhotoDimEvalUiState(message = out.message)
                is PhotoDimEval.Outcome.Failed -> PhotoDimEvalUiState(message = out.message)
            }
        }
        job?.invokeOnCompletion { c -> if (c != null) _state.value = PhotoDimEvalUiState(message = "Cancelled") }
    }

    fun cancel() { job?.cancel() }

    companion object { const val NOT_READY = "Download the Nomic Embed model first (Settings > AI > On-device models)." }
}

/** Production wiring: the downloaded bundle (not the bound space), photos opened in-app, the same thermal and battery gate as backfill. */
object PhotoDimEvalFactory {
    fun create(app: Context): PhotoDimEval {
        val models = LocalModelStore.modelsDir(app)
        val text = File(models, "nomic-embed-text-v1.5.Q8_0.gguf")
        val vision = File(models, NomicBundle.VISION_FILE)
        val policy = PhonePowerPolicy({ System.currentTimeMillis() }, { true }, { KeyframeGraph.thermal.status() })
        val embedder = object : PhotoDimEval.Embedder {
            override suspend fun images(jpegs: List<ByteArray>) = NomicBundle.imageEmbedder(vision.path, NomicVision.STORED_DIMS).rawCls(jpegs)
            override suspend fun queries(prefixed: List<String>) = withContext(Dispatchers.Default) {
                LlamaCppNativeProvider.embed(text.path, prefixed, normalize = false)
            }
        }
        val loader = PhotoDimEval.PhotoLoader { ids ->
            FramePhotos.load(app, ids).photos.associate { it.frameId to Base64.decode(it.image.base64Data, Base64.NO_WRAP) }
        }
        return PhotoDimEval(
            File(app.filesDir, "photo-eval"), loader, embedder,
            gate = { policy.mayRunBackground(BackgroundWork.BACKFILL) && LocalModelPower.bulkAllowed(LocalModelPower.read(app)) },
        )
    }

    fun bundleReady(app: Context): Boolean = File(LocalModelStore.modelsDir(app), NomicBundle.VISION_FILE).isFile &&
        File(LocalModelStore.modelsDir(app), "nomic-embed-text-v1.5.Q8_0.gguf").isFile
}

@Composable
fun PhotoDimEvalScreen(state: PhotoDimEvalUiState, onBack: () -> Unit, onRun: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().background(PcColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Photo search dimension test", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Debug only. Reads files/photo-eval/queries.json (pushed with run-as), embeds your labelled photos at 768 and at 256 dimensions in memory, " +
                        "and compares recall@3. Nothing is written to your search index. Needs at least ${PhotoDimEval.MIN_PHOTOS} photos, ${PhotoDimEval.MIN_QUERIES} queries and ${PhotoDimEval.MIN_SMALL} small-object queries.",
                    color = PcColors.InkSoft,
                )
                if (state.running) PrimaryAction("Cancel", onCancel, tag = "photo_dim_cancel") else PrimaryAction("Run test", onRun, tag = "photo_dim_run")
                state.progress?.takeIf { state.running }?.let { NoticeText(it, tag = "photo_dim_progress") }
                state.message?.let { NoticeText(it, tag = "photo_dim_message") }
            }
        }
        if (state.lines.isNotEmpty()) {
            SectionLabel("Result")
            SoftCard(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(16.dp)) { state.lines.forEachIndexed { i, l -> NoticeText(l, tag = "photo_dim_line_$i") } }
            }
        }
    }
}
