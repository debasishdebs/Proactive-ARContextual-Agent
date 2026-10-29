package com.rokid.context.phone.ui.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.components.FrameImage
import com.rokid.context.phone.ui.memory.Zoom

@OptIn(ExperimentalFoundationApi::class) // transformable(canPan) is experimental in foundation 1.7
@Composable
fun ZoomableFrame(path: String, modifier: Modifier) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = Zoom.clamp(scale * zoom)
        val next = offset + pan
        offset = if (scale == Zoom.MIN) Offset.Zero else Offset(Zoom.clampPan(next.x, scale, size.width.toFloat()), Zoom.clampPan(next.y, scale, size.height.toFloat()))
    }
    // Pan only once zoomed: at unit scale a one-finger drag belongs to the page's verticalScroll (device check D-E1;
    // in landscape the photo fills the viewport). Pinch still zooms at any scale.
    Box(modifier.clip(RoundedCornerShape(20.dp)).onSizeChanged { size = it }.transformable(transform, canPan = { scale > Zoom.MIN })) {
        FrameImage(path, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
    }
}
