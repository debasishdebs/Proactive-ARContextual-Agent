package com.rokid.context.phone.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.BuildConfig
import com.rokid.context.core.ui.components.ConfidenceChip
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.SectionLabel
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.memory.MemoryDetailModel
import com.rokid.context.phone.ui.memory.MemoryDetailUiState
import kotlinx.coroutines.delay
import java.time.LocalDate

/** What a section reaches back into the screen for. Sections hold ids and plain text only; a photo's path is looked up (and decoded by the image view) when it is on screen. */
data class PreviewHost(
    /** The memory detail state: the live caption, its draft and the Forget dialog's flags. */
    val detail: MemoryDetailUiState,
    /** The stored path of a photo (null: retention deleted it). Called lazily, once per photo shown. */
    val framePath: suspend (String) -> String?,
    /** "Show that time": the day view, scrolled to [atMs] (null: the top). */
    val showTime: (LocalDate, Long?) -> Unit = { _, _ -> },
    val forgetPhoto: (String) -> Unit = {},
    val trust: () -> Unit = {},
    val edit: () -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val saveEdit: () -> Unit = {},
    val cancelEdit: () -> Unit = {},
    /** Opens another item by its [PreviewRef.raw] (a cited memory or photo, "Open this day's photos"). */
    val open: (String) -> Unit = {},
    /** Rates the answer on screen (true = helpful). */
    val rate: (Boolean) -> Unit = {},
    /** "More photos": the photo search on a question. */
    val morePhotos: (String) -> Unit = {},
    /** The live action card (ProposalCard) of the item with this id; nothing in a test host. */
    val proposal: @Composable (String) -> Unit = {},
    /** Labels a photo for the photo 256-vs-768 test (query, frame id, small); null unless a debug build with Developer mode. */
    val addToPhotoTest: ((String, String, Boolean) -> Unit)? = null,
)

/** A cited memory or photo. [ref] is what a tap previews (null: expired, inert); [frameId] is the thumbnail's photo. */
data class SourceItem(val ref: String?, val text: String, val confidence: Double? = null, val frameId: String? = null, val large: Boolean = false, val expired: Boolean = false, val photoId: String? = null)

private const val PLAY_STEP_MS = 1_500L

enum class Severity { INFO, WARNING, ERROR }

/**
 * One block of the previewer. A section is self-rendering and owns its own actions, so the screen never branches on what kind of item it shows: it lays the sections out in order.
 * A new kind of content adds a section here (and a [Previewable] that returns it).
 */
sealed interface PreviewSection {
    @Composable fun Content(host: PreviewHost)

    /**
     * Photos by id, lazily loaded. Several ids page by swipe (a day's photos, with a slideshow), opened at [startId]; [labels] (same order, "09:30") name each.
     * [canForgetPhoto] adds "Forget photo" for the photo on screen; [openDay] adds "Open this day's photos" (the photo's day, paged).
     */
    data class ImageSection(val frameIds: List<String>, val startId: String? = null, val canForgetPhoto: Boolean = false, val labels: List<String> = emptyList(), val openDay: Boolean = false) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = ImageContent(this, host)
    }

    /** A block of text. With [memoryId] it is a memory's caption: it follows an edit and carries Trust and Edit. */
    data class TextSection(val label: String?, val body: String, val memoryId: String? = null) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = TextContent(this, host)
    }

    /** A fixed [code] (`camera_busy`, `pending`...) with its words. [showTimeOn] adds "Show that time". */
    data class StatusSection(val code: String, val message: String, val severity: Severity, val showTimeOn: LocalDate? = null, val showTimeAtMs: Long? = null) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = StatusContent(this, host)
    }

    /** Label to value, fixed known keys only (never a secret or a key). [live]: Level and Confidence follow the memory detail's live state (a Trust shows at once). */
    data class ConfigSection(val entries: List<Pair<String, String>>, val live: Boolean = false) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = ConfigContent(this, host)
    }

    /** The answer's thumbs. [rating] is 1, -1 or null; the choice is shown at once and sent through [PreviewHost.rate]. */
    data class RatingSection(val rating: Int?) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = RatingContent(this, host)
    }

    /** The live action card (proposal, confirm, undo) of the item [originId]. */
    data class ProposalSection(val originId: String) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) { host.proposal(originId) }
    }

    /** What the item was based on: cited memories and photos, each opening its own previewer. [missing] sources no longer exist; [moreQuery] adds "More photos". */
    data class SourcesSection(val label: String, val items: List<SourceItem>, val missing: Int = 0, val moreQuery: String? = null) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = SourcesContent(this, host)
    }

    /** Always present: a greyed-out player that cannot play (nothing records audio yet), so the layout does not change when it does. */
    data object AudioSection : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = AudioContent()
    }
}

private sealed interface Shown { data object Loading : Shown; data object Expired : Shown; data class Ok(val path: String) : Shown }

@Composable
private fun ImageContent(s: PreviewSection.ImageSection, host: PreviewHost) {
    if (s.frameIds.isEmpty()) return
    val pager = rememberPagerState(initialPage = s.frameIds.indexOf(s.startId).coerceAtLeast(0)) { s.frameIds.size }
    var playing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(playing, pager.currentPage) {
        if (!playing) return@LaunchedEffect
        delay(PLAY_STEP_MS)
        if (pager.currentPage < s.frameIds.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else playing = false
    }
    val current = s.frameIds[pager.currentPage.coerceIn(0, s.frameIds.lastIndex)]
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("preview_image")) {
        HorizontalPager(pager, key = { s.frameIds[it] }, modifier = Modifier.fillMaxWidth().height(260.dp).testTag("frame_pager")) { page ->
            val id = s.frameIds[page]
            val shown by produceState<Shown>(Shown.Loading, id) { value = host.framePath(id)?.let { Shown.Ok(it) } ?: Shown.Expired }
            when (val x = shown) {
                is Shown.Ok -> ZoomableFrame(x.path, Modifier.fillMaxWidth().height(260.dp))
                Shown.Expired -> Text(MemoryDetailModel.PHOTO_EXPIRED, style = MaterialTheme.typography.labelLarge, color = PcColors.InkSoft, modifier = Modifier.padding(top = 8.dp).testTag("photo_expired"))
                Shown.Loading -> Unit
            }
        }
        if (s.frameIds.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${s.frameIds.size}" + s.labels.getOrNull(pager.currentPage)?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.weight(1f))
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = PcColors.InkSoft) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.openDay) TextButton(onClick = { host.open(PreviewRef.of(RefKind.FRAME, current)) }, modifier = Modifier.testTag("open_day")) { Text("Open this day's photos", color = PcColors.MintDeep) }
            if (s.canForgetPhoto) TextButton(onClick = { host.forgetPhoto(current) }, enabled = !host.detail.forgetting, modifier = Modifier.testTag("forget_photo")) { Text("Forget photo", color = PcColors.MintDeep) }
        }
        host.addToPhotoTest?.let { add ->
            var asking by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { asking = true }, modifier = Modifier.testTag("add_to_photo_test")) { Text("Add to photo test", color = PcColors.MintDeep) }
            if (asking) AddToPhotoTestDialog(onDismiss = { asking = false }, onAdd = { q, small -> asking = false; add(q, current, small) })
        }
        if (BuildConfig.DEBUG) {
            // Debug builds: copy the id to label photos for the photo 256-vs-768 test (ids are not shown or logged anywhere).
            val clipboard = LocalClipboardManager.current
            TextButton(onClick = { clipboard.setText(AnnotatedString(current)) }, modifier = Modifier.testTag("copy_frame_id")) { Text("Copy frame id", color = PcColors.MintDeep) }
        }
    }
}

/** Asks for a short query this photo should answer, and whether the thing sought is small or near the edge of the frame. */
@Composable
fun AddToPhotoTestDialog(onDismiss: () -> Unit, onAdd: (query: String, small: Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var small by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to photo test") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text("What would you search for?") }, singleLine = true, modifier = Modifier.testTag("photo_test_query"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(small, { small = it }, modifier = Modifier.testTag("photo_test_small"))
                    Text("Small or edge object")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(query.trim(), small) }, enabled = query.isNotBlank(), modifier = Modifier.testTag("photo_test_add")) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RatingContent(s: PreviewSection.RatingSection, host: PreviewHost) {
    var rating by remember(s.rating) { mutableStateOf(s.rating) }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("preview_rating"), horizontalArrangement = Arrangement.End) {
        IconButton(onClick = { rating = 1; host.rate(true) }, modifier = Modifier.semantics { selected = rating == 1 }) {
            Icon(if (rating == 1) Icons.Rounded.ThumbUp else Icons.Outlined.ThumbUp, contentDescription = "Helpful", tint = PcColors.MintDeep)
        }
        IconButton(onClick = { rating = -1; host.rate(false) }, modifier = Modifier.semantics { selected = rating == -1 }) {
            Icon(if (rating == -1) Icons.Rounded.ThumbDown else Icons.Outlined.ThumbDown, contentDescription = "Not helpful", tint = PcColors.ChipCoral.second)
        }
    }
}

@Composable
private fun SourcesContent(s: PreviewSection.SourcesSection, host: PreviewHost) {
    Column(Modifier.testTag("preview_sources")) {
        SectionLabel(s.label)
        if (s.items.isEmpty() && s.missing == 0) Text("No saved memories were cited", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp))
        s.items.forEach { SourceCardRow(it, host) }
        s.moreQuery?.let { q -> TextButton(onClick = { host.morePhotos(q) }, modifier = Modifier.padding(horizontal = 8.dp).testTag("more_photos")) { Text("More photos") } }
        if (s.missing > 0) {
            Text("${s.missing} source${if (s.missing == 1) " is" else "s are"} no longer available", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

/** A cited memory (its text, photo and confidence) or photo (a large thumbnail, or "Photo expired" once it is gone); a tap previews it. */
@Composable
private fun SourceCardRow(src: SourceItem, host: PreviewHost) {
    val path by produceState<String?>(null, src.frameId) { value = src.frameId?.let { id -> host.framePath(id) } }
    val onClick = src.ref?.let { r -> { host.open(r) } }
    val photo = src.photoId != null
    val label = if (src.expired) MemoryDetailModel.PHOTO_EXPIRED else "Photo, opens the photo previewer"
    val tag = if (photo) Modifier.testTag("photo_source_${src.photoId}").semantics(mergeDescendants = true) { contentDescription = label } else Modifier
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().then(tag), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FrameThumb(if (src.expired) null else path, size = if (src.large) 96.dp else 52.dp)
            Text(
                if (src.expired) MemoryDetailModel.PHOTO_EXPIRED else src.text, style = MaterialTheme.typography.bodyMedium, color = PcColors.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            src.confidence?.let { c -> ConfidenceChip(c) }
        }
    }
}

@Composable
private fun TextContent(s: PreviewSection.TextSection, host: PreviewHost) {
    val d = host.detail
    val memory = s.memoryId != null && d.found
    // A forgotten or missing memory never shows its cached words.
    if (s.memoryId != null && !d.loading && !d.found) {
        Text("This memory is no longer available", style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = Modifier.padding(20.dp).testTag("preview_missing"))
        return
    }
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().testTag("preview_text")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft) }
            val draft = d.draft.takeIf { memory }
            if (draft != null) {
                OutlinedTextField(value = draft, onValueChange = host.onDraftChange, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton("Save", onClick = host.saveEdit)
                    TextButton(onClick = host.cancelEdit) { Text("Cancel") }
                }
            } else {
                Text(if (memory) d.text else s.body, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
            }
            if (s.memoryId != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GradientButton(if (d.trusted) "Trusted" else "Trust", onClick = host.trust, enabled = memory && d.editable && !d.trusted && d.draft == null)
                    OutlinedButton(onClick = host.edit, enabled = memory && d.editable && d.draft == null) { Text("Edit") }
                }
            }
        }
    }
}

@Composable
private fun StatusContent(s: PreviewSection.StatusSection, host: PreviewHost) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().testTag("preview_status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = if (s.severity == Severity.ERROR) PcColors.Ink else PcColors.InkSoft)
            s.showTimeOn?.let { day -> OutlinedButton(onClick = { host.showTime(day, s.showTimeAtMs) }, modifier = Modifier.testTag("mark_show_time")) { Text("Show that time") } }
        }
    }
}

@Composable
private fun ConfigContent(s: PreviewSection.ConfigSection, host: PreviewHost) {
    val d = host.detail
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("preview_config"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        s.entries.map { (k, v) ->
            when {
                s.live && d.found && k == "Level" -> k to "${d.tierLabel} \u00b7 ${d.reviewLabel}${if (d.trusted) " \u00b7 Trusted" else ""}"
                s.live && d.found && k == "Confidence" -> k to "${(d.confidence * 100).toInt()}%"
                else -> k to v
            }
        }.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft) }
    }
}

@Composable
private fun AudioContent() {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PcColors.SurfaceSoft).padding(12.dp).testTag("preview_audio"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.PlayArrow, contentDescription = "Audio player, no audio", tint = PcColors.InkSoft.copy(alpha = 0.4f))
        Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(PcColors.Line))
        Text("No audio", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft.copy(alpha = 0.6f))
    }
}
