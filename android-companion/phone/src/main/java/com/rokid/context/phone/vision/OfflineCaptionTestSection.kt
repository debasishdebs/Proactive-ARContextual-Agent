package com.rokid.context.phone.vision

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.rokid.context.core.model.vision.CaptionRefused
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Developer test for the offline captioner (V1): pick a photo, the phone captions it with the on-device model, the text and the latency are shown. NOTHING is stored: the picked photo is
 * copied to a cache file that is deleted straight after, and the caption lives only in this composable's state. The caption and the file are content: never logged.
 */
@Composable
fun OfflineCaptionTestSection(app: Context) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true; result = "Working…"
        scope.launch {
            result = runCaptionTest(app, uri)
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().testTag("offline_caption_test")) {
        Text("Caption a photo (test)", style = MaterialTheme.typography.titleSmall)
        Text("Pick a photo: the text it gets and the time taken are shown here. Nothing is saved or sent. The model is freed about 5 seconds later.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
        OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy, modifier = Modifier.testTag("offline_caption_pick")) { Text("Pick a photo") }
        result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("offline_caption_result")) }
    }
}

/** Copies the picked photo to a temporary file, captions it, deletes the file, and returns the text to show (the caption plus latency, or a fixed failure message). */
private suspend fun runCaptionTest(app: Context, uri: Uri): String {
    val tmp = File(app.cacheDir, "offline-caption-test.jpg")
    try {
        val copied = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } != null }.getOrDefault(false)
        }
        if (!copied) return "Could not read that photo."
        val t0 = System.nanoTime()
        val r = OfflineCaptionerHolder.get(app).caption(tmp.path)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return r.fold({ "$it\n\n${ms} ms" }, { e -> refusalText(e) })
    } catch (e: CancellationException) {
        throw e
    } finally {
        tmp.delete()
    }
}

internal fun refusalText(e: Throwable): String = when ((e as? CaptionRefused)?.reason?.name) {
    "DISABLED" -> "Offline photo captions are off."
    "MODEL_MISSING" -> "Download SmolVLM2 first (Settings > AI > Local models)."
    "THERMAL" -> "The phone is too hot. Try again when it has cooled."
    "BATTERY_SAVER" -> "Battery saver is on."
    "BATTERY_LOW" -> "Battery is under 20% and not charging."
    "MAIN_THREAD" -> "Cannot run on the main thread."
    "BAD_IMAGE" -> "Could not read that photo."
    else -> "The model could not describe that photo."
}
