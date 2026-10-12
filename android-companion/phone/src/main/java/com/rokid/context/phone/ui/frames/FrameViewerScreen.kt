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
fun FrameViewerScreen(state: FrameViewerUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
            Text(state.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
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
