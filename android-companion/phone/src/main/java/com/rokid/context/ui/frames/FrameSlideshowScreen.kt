package com.rokid.context.ui.frames

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

data class FrameSlide(
    val id: String,
    val timestamp: String,
    val source: String,
    val payload: String,
    val summaryHint: String,
    val imagePath: String,
)

object FrameSlideshowScreen {
    @Composable
    fun Render(
        slides: List<FrameSlide>,
        selectedIndex: Int,
        onSelectIndex: (Int) -> Unit,
        onPrev: () -> Unit,
        onNext: () -> Unit,
        onBack: () -> Unit,
    ) {
        val safeIndex = selectedIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
        val current = slides.getOrNull(safeIndex)
        var isFullScreen by rememberSaveable { mutableStateOf(false) }
        var isPlaying by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(isFullScreen, isPlaying, safeIndex, slides.size) {
            if (isFullScreen && isPlaying && slides.size > 1) {
                delay(1800)
                val next = if (safeIndex >= slides.lastIndex) 0 else safeIndex + 1
                onSelectIndex(next)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recent Frames Slideshow", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.linearGradient(listOf(Color(0xFF6B3C9D), Color(0xFF3A255D))))
                        .padding(12.dp),
                ) {
                    if (current == null) {
                        Text("No frames captured yet", color = Color.White)
                    } else {
                        Text("Frame ${safeIndex + 1}/${slides.size}", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("Time: ${current.timestamp}", color = Color(0xFFEFE8FF))
                        Text("Source: ${current.source}", color = Color(0xFFEFE8FF))

                        val previewBitmap = remember(current.imagePath) {
                            if (current.imagePath.isBlank()) null else BitmapFactory.decodeFile(current.imagePath)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp)),
                        ) {
                            if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = "Frame Preview",
                                    modifier = Modifier.fillMaxWidth().height(220.dp),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .background(Color(0x332A1A45)),
                                )
                            }

                            Card(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .align(Alignment.BottomStart),
                            ) {
                                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("ID: ${current.id}", fontWeight = FontWeight.Bold)
                                    Text(current.payload.take(95))
                                }
                            }
                        }
                        Text("Overlay Summary: ${current.summaryHint}", color = Color(0xFFEFE8FF))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrev, enabled = slides.size > 1 && safeIndex > 0) { Text("Prev") }
                Button(onClick = onNext, enabled = slides.size > 1 && safeIndex < slides.lastIndex) { Text("Next") }
                Button(onClick = { isFullScreen = true }, enabled = current != null) { Text("Full Screen") }
            }
            Button(onClick = onBack) { Text("Back") }
            Text(
                "Note: Triggering summarizer does not create a new frame. Use frame capture to add slideshow images.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (isFullScreen && current != null) {
            val previewBitmap = remember(current.imagePath) {
                if (current.imagePath.isBlank()) null else BitmapFactory.decodeFile(current.imagePath)
            }
            Dialog(
                onDismissRequest = {
                    isPlaying = false
                    isFullScreen = false
                },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Frame Fullscreen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF191919)))
                    }

                    Card(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x99000000)),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Frame ${safeIndex + 1}/${slides.size}", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("ID: ${current.id}", color = Color.White)
                            Text("Time: ${current.timestamp}  Source: ${current.source}", color = Color.White)
                            Text(current.payload.take(130), color = Color(0xFFE4E4E4))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val prev = if (safeIndex <= 0) slides.lastIndex else safeIndex - 1
                                onSelectIndex(prev)
                            },
                            enabled = slides.size > 1,
                        ) { Text("Prev") }
                        Button(
                            onClick = { isPlaying = !isPlaying },
                            enabled = slides.size > 1,
                        ) { Text(if (isPlaying) "Pause" else "Play") }
                        Button(
                            onClick = {
                                val next = if (safeIndex >= slides.lastIndex) 0 else safeIndex + 1
                                onSelectIndex(next)
                            },
                            enabled = slides.size > 1,
                        ) { Text("Next") }
                        Button(
                            onClick = {
                                isPlaying = false
                                isFullScreen = false
                            },
                        ) { Text("Exit") }
                    }
                }
            }
        }
    }
}
