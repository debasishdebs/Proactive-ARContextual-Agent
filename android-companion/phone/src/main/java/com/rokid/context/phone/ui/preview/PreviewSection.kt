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
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.GradientButton
import com.rokid.context.core.ui.components.SoftCard
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.memory.MemoryDetailModel
import com.rokid.context.phone.ui.memory.MemoryDetailUiState
import com.rokid.context.phone.ui.memory.ZoomableFrame
import java.time.LocalDate

/** What a section reaches back into the screen for. Sections hold ids and plain text only; a photo's path is looked up (and decoded by the image view) when it is on screen. */
class PreviewHost(
    /** The memory detail state: the live caption, its draft and the Forget dialog's flags. */
    val detail: MemoryDetailUiState,
    /** The stored path of a photo (null: retention deleted it). Called lazily, once per photo shown. */
    val framePath: suspend (String) -> String?,
    val showTime: (LocalDate) -> Unit = {},
    val forgetPhoto: (String) -> Unit = {},
    val trust: () -> Unit = {},
    val edit: () -> Unit = {},
    val onDraftChange: (String) -> Unit = {},
    val saveEdit: () -> Unit = {},
    val cancelEdit: () -> Unit = {},
)

enum class Severity { INFO, WARNING, ERROR }

/**
 * One block of the previewer. A section is self-rendering and owns its own actions, so the screen never branches on what kind of item it shows: it lays the sections out in order.
 * A new kind of content adds a section here (and a [Previewable] that returns it).
 */
sealed interface PreviewSection {
    @Composable fun Content(host: PreviewHost)

    /** Photos by id, lazily loaded. Several ids page by swipe (a day's photos), opened at [startId]. [canForgetPhoto] adds "Forget photo" for the photo on screen. */
    data class ImageSection(val frameIds: List<String>, val startId: String? = null, val canForgetPhoto: Boolean = false) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = ImageContent(this, host)
    }

    /** A block of text. With [memoryId] it is a memory's caption: it follows an edit and carries Trust and Edit. */
    data class TextSection(val label: String?, val body: String, val memoryId: String? = null) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = TextContent(this, host)
    }

    /** A fixed [code] (`camera_busy`, `pending`...) with its words. [showTimeOn] adds "Show that time". */
    data class StatusSection(val code: String, val message: String, val severity: Severity, val showTimeOn: LocalDate? = null) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = StatusContent(this, host)
    }

    /** Label to value, fixed known keys only (never a secret or a key). */
    data class ConfigSection(val entries: List<Pair<String, String>>) : PreviewSection {
        @Composable override fun Content(host: PreviewHost) = ConfigContent(this)
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
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("preview_image")) {
        HorizontalPager(pager, key = { s.frameIds[it] }, modifier = Modifier.fillMaxWidth().height(260.dp)) { page ->
            val id = s.frameIds[page]
            val shown by produceState<Shown>(Shown.Loading, id) { value = host.framePath(id)?.let { Shown.Ok(it) } ?: Shown.Expired }
            when (val x = shown) {
                is Shown.Ok -> ZoomableFrame(x.path, Modifier.fillMaxWidth().height(260.dp))
                Shown.Expired -> Text(MemoryDetailModel.PHOTO_EXPIRED, style = MaterialTheme.typography.labelLarge, color = PcColors.InkSoft, modifier = Modifier.padding(top = 8.dp).testTag("photo_expired"))
                Shown.Loading -> Unit
            }
        }
        if (s.frameIds.size > 1) Text("${pager.currentPage + 1} / ${s.frameIds.size}", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft)
        if (s.canForgetPhoto) TextButton(onClick = { host.forgetPhoto(s.frameIds[pager.currentPage.coerceIn(0, s.frameIds.lastIndex)]) }, enabled = !host.detail.forgetting, modifier = Modifier.testTag("forget_photo")) { Text("Forget photo", color = PcColors.MintDeep) }
    }
}

@Composable
private fun TextContent(s: PreviewSection.TextSection, host: PreviewHost) {
    val d = host.detail
    val memory = s.memoryId != null && d.found
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
            if (memory) d.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = PcColors.MintDeep) }
        }
    }
}

@Composable
private fun StatusContent(s: PreviewSection.StatusSection, host: PreviewHost) {
    SoftCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().testTag("preview_status")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = if (s.severity == Severity.ERROR) PcColors.Ink else PcColors.InkSoft)
            s.showTimeOn?.let { day -> OutlinedButton(onClick = { host.showTime(day) }, modifier = Modifier.testTag("mark_show_time")) { Text("Show that time") } }
        }
    }
}

@Composable
private fun ConfigContent(s: PreviewSection.ConfigSection) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("preview_config"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        s.entries.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.labelMedium, color = PcColors.InkSoft) }
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
