package com.rokid.context.phone.ui.frames

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.FrameImage
import kotlinx.coroutines.delay

private const val PLAY_STEP_MS = 1_500L

/** Full-screen day viewer (spec §2.4 Frame viewer). PillNav is hidden on this route (Routes.showsNav). */
@Composable
fun FrameViewerScreen(
    state: FrameViewerUiState, onBack: () -> Unit,
    /** Debug builds with Developer mode on only (null otherwise): label this photo for the photo 256-vs-768 test (query text, small-object flag, frame id). */
    onAddToPhotoTest: ((query: String, frameId: String, small: Boolean) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
            Text(state.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        if (state.startExpired) {
            Text(com.rokid.context.phone.ui.memory.MemoryDetailModel.PHOTO_EXPIRED, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("photo_expired"))
        }
        when {
            state.loading -> Unit
            !state.found -> Message("Day not found")
            state.frames.isEmpty() -> Message("No photos for this day")
            else -> key(state.title) {
                val pager = rememberPagerState(initialPage = state.startIndex.coerceIn(0, state.frames.lastIndex)) { state.frames.size }
                var playing by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(playing, pager.currentPage) {
                    if (!playing) return@LaunchedEffect
                    delay(PLAY_STEP_MS)
                    if (pager.currentPage < state.frames.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else playing = false
                }
                HorizontalPager(pager, key = { state.frames[it].id }, modifier = Modifier.weight(1f).fillMaxWidth().testTag("frame_pager")) { page ->
                    FrameImage(state.frames[page].path, Modifier.fillMaxSize())
                }
                val current = state.frames[pager.currentPage.coerceIn(0, state.frames.lastIndex)]
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${pager.currentPage + 1} / ${state.frames.size} · ${current.time}", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (onAddToPhotoTest != null) {
                        var asking by rememberSaveable { mutableStateOf(false) }
                        androidx.compose.material3.TextButton(onClick = { asking = true }, modifier = Modifier.testTag("add_to_photo_test")) { Text("Add to photo test", color = Color.White) }
                        if (asking) AddToPhotoTestDialog(onDismiss = { asking = false }, onAdd = { q, small -> asking = false; onAddToPhotoTest(q, current.id, small) })
                    }
                    if (com.rokid.context.BuildConfig.DEBUG) {
                        // Debug builds: copy the id to label photos for the photo 256-vs-768 test (ids are not shown or logged anywhere).
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        androidx.compose.material3.TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(current.id)) }, modifier = Modifier.testTag("copy_frame_id")) {
                            Text("Copy frame id", color = Color.White)
                        }
                    }
                    IconButton(onClick = { playing = !playing }) {
                        Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium) }
}

/** Asks for a short query this photo should answer, and whether the thing sought is small or near the edge of the frame. */
@Composable
fun AddToPhotoTestDialog(onDismiss: () -> Unit, onAdd: (query: String, small: Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var small by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to photo test") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(query, { query = it }, label = { Text("What would you search for?") }, singleLine = true, modifier = Modifier.testTag("photo_test_query"))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(small, { small = it }, modifier = Modifier.testTag("photo_test_small"))
                    Text("Small or edge object")
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onAdd(query.trim(), small) }, enabled = query.isNotBlank(), modifier = Modifier.testTag("photo_test_add")) { Text("Add") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
