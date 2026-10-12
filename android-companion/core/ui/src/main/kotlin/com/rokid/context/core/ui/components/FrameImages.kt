package com.rokid.context.core.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Downsampled JPEG decoding for frame thumbnails; never throws, never runs on the main thread (callers use IO). */
object FrameDecode {
    /** Largest power-of-two sample size that keeps both sides ≥ [targetPx]. */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        if (width <= 0 || height <= 0 || targetPx <= 0) return 1
        var s = 1
        while (width / (s * 2) >= targetPx && height / (s * 2) >= targetPx) s *= 2
        return s
    }

    /** Null when the path is blank, the file is missing/empty, or it is not a decodable image. */
    fun decode(path: String?, targetPx: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx) })
        }.getOrNull()
    }
}

private sealed interface FrameLoad {
    data object Loading : FrameLoad
    data object Missing : FrameLoad
    data class Loaded(val path: String?, val targetPx: Int, val bitmap: ImageBitmap) : FrameLoad
}

@Composable
private fun rememberFrame(path: String?, targetPx: Int): FrameLoad {
    val current by produceState<FrameLoad>(FrameLoad.Loading, path, targetPx) {
        value = FrameLoad.Loading
        value = withContext(Dispatchers.IO) { FrameDecode.decode(path, targetPx) }?.let { FrameLoad.Loaded(path, targetPx, it.asImageBitmap()) } ?: FrameLoad.Missing
    }
    // produceState keeps its last value across key changes; never show a photo decoded for a different path/size.
    val load = current
    return if (load is FrameLoad.Loaded && (load.path != path || load.targetPx != targetPx)) FrameLoad.Loading else load
}

/** Rounded square thumbnail (timeline rows, "Based on" source cards). Missing file → placeholder icon. */
@Composable
fun FrameThumb(path: String?, modifier: Modifier = Modifier, size: Dp = 56.dp, onClick: (() -> Unit)? = null) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val load = rememberFrame(path, px)
    Box(
        modifier.size(size).clip(RoundedCornerShape(12.dp)).background(PcColors.SurfaceSoft)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            is FrameLoad.Loaded -> Image(load.bitmap, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            FrameLoad.Loading -> Unit
            FrameLoad.Missing -> Icon(Icons.Outlined.ImageNotSupported, contentDescription = "No photo", tint = PcColors.InkSoft, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** Large frame on black (Memory detail, Frame viewer). [maxPx] bounds the decoded size. */
@Composable
fun FrameImage(path: String?, modifier: Modifier = Modifier, maxPx: Int = 1440, contentScale: ContentScale = ContentScale.Fit) {
    val load = rememberFrame(path, maxPx)
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (load) {
            is FrameLoad.Loaded -> Image(load.bitmap, contentDescription = "Photo", contentScale = contentScale, modifier = Modifier.fillMaxSize())
            FrameLoad.Loading -> Unit
            FrameLoad.Missing -> Text("Photo no longer on this phone", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
