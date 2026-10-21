package com.rokid.context.phone.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.FrameThumb
import com.rokid.context.core.ui.components.ScreenHeader
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.memory.MemoryDetailModel

/**
 * Find a photo (wave 3 Task 4; spec §3.2). Stateless. Searches on IME Search or the button only. Photos are shown through [FrameThumb], which decrypts
 * through the app's frame opener in memory only (no file is written, nothing is shared outside the app); an expired photo is the metadata-only tile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSearchScreen(
    state: PhotoSearchUiState, onBack: () -> Unit, onQueryChange: (String) -> Unit, onSubmit: () -> Unit,
    onDescribe: (String) -> Unit, onDescribeScene: (String) -> Unit,
    /** (date yyyy-MM-dd, frame id): the frame viewer, for a stored photo. */
    onOpenFrame: (String, String) -> Unit, onOpenMemory: (String) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = state.tiles.firstOrNull { it.frameId == selectedId }
    Column(Modifier.fillMaxSize().background(PcColors.Surface)) {
        ScreenHeader("Find a photo", onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.query, onValueChange = onQueryChange, singleLine = true, label = { Text("Describe the photo") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.weight(1f).testTag("photo_query"),
            )
            IconButton(onClick = onSubmit, modifier = Modifier.testTag("photo_submit")) { Icon(Icons.Outlined.Search, contentDescription = "Search photos", tint = PcColors.Ink) }
        }
        // Pinned above the grid, so a status or failure is never scrolled away (M6).
        state.banner?.let { Note(it, Modifier.padding(horizontal = 12.dp).testTag("photo_banner")) }
        state.note?.let { Note(it, Modifier.padding(horizontal = 12.dp).testTag("photo_note")) }
        state.status?.let { Note(it, Modifier.padding(horizontal = 12.dp).testTag("photo_status").semantics { liveRegion = LiveRegionMode.Polite }) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.searching) item(span = { GridItemSpan(maxLineSpan) }) {
                CircularProgressIndicator(Modifier.padding(16.dp).semantics { contentDescription = "Searching" })
            }
            items(state.tiles, key = { it.frameId }) { t -> TileCell(t) { selectedId = t.frameId } }
        }
    }
    if (selected != null) {
        ModalBottomSheet(onDismissRequest = { selectedId = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (selected.expired) "${MemoryDetailModel.PHOTO_EXPIRED} · ${selected.timeLabel}".trimEnd(' ', '·') else selected.timeLabel, style = MaterialTheme.typography.titleMedium, color = PcColors.Ink)
                val canOpen = !selected.expired || selected.captionMemoryId != null
                if (canOpen) {
                    TextButton(
                        onClick = {
                            selectedId = null
                            if (!selected.expired) onOpenFrame(selected.date, selected.frameId) else selected.captionMemoryId?.let(onOpenMemory)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("open_photo"),
                    ) { Text(if (selected.expired) "Open caption" else "Open") }
                } else {
                    TextButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("open_disabled")) { Text("Open") }
                }
                TextButton(
                    onClick = { selectedId = null; onDescribe(selected.frameId) }, enabled = !selected.expired && !state.describing,
                    modifier = Modifier.fillMaxWidth().testTag("describe_photo"),
                ) { Text("Describe this photo") }
                selected.clusterId?.let {
                    TextButton(
                        onClick = { selectedId = null; onDescribeScene(it) }, enabled = !state.describing,
                        modifier = Modifier.fillMaxWidth().testTag("describe_scene"),
                    ) { Text("Describe this scene") }
                }
                TextButton(onClick = { selectedId = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft, modifier = modifier.padding(horizontal = 4.dp, vertical = 8.dp))
}

@Composable
private fun TileCell(t: PhotoTile, onClick: () -> Unit) {
    val description = buildString {
        append(if (t.expired) "Photo expired" else "Photo"); if (t.timeLabel.isNotEmpty()) append(", ${t.timeLabel}")
        append(", match ${t.score}, ${t.badge} search"); if (t.described) append(", described")
    }
    Column(
        Modifier.clickable(onClick = onClick).testTag("photo_tile_${t.frameId}").semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FrameThumb(if (t.expired) null else t.path, size = 96.dp)
        if (t.expired) {
            Text(if (t.caption == null) "Photo expired · no caption" else "Photo expired", style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft, modifier = Modifier.testTag("expired_${t.frameId}"))
            t.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = PcColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (t.timeLabel.isNotEmpty()) Text(t.timeLabel, style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${t.score} · ${t.badge}", style = MaterialTheme.typography.labelSmall, color = PcColors.InkSoft)
            if (t.described) Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = PcColors.MintDeep, modifier = Modifier.padding(start = 4.dp).testTag("described_${t.frameId}"))
        }
    }
}
