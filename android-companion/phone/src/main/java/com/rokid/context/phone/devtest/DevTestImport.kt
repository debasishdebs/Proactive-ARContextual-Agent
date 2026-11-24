package com.rokid.context.phone.devtest

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.ai.Http
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.FrameSources
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.phone.capture.PhoneCapture
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.PrimaryAction
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.place.PinForm
import com.rokid.context.work.frames.place.PinRefusal
import com.rokid.context.work.frames.place.PinResult
import com.rokid.context.work.frames.place.PlacePinning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * WP8 (Developer only): a test place and test photos, so place cards can be tried at an ordinary shop. A test place is a normal place pin (note [NOTE]); a test photo is a normal frame stored
 * with the source [FrameSources.DEV_IMPORT] (the "imported" marker: the detail screen says so and the memory export leaves it out) whose Moment id is the one reference the pin keeps (the pin points to
 * the Moment; a Moment never points to the pin). Forgetting works through the existing paths: the frame is an ordinary frame row and file, the pin an ordinary pin.
 */
class DevTestImport(
    private val pinning: PlacePinning,
    /** Stores the image bytes as an imported frame and returns its capture time (ms), or null when it was refused or could not be stored. */
    private val ingest: suspend (ByteArray) -> Long?,
    private val fetch: suspend (String) -> Result<ByteArray>,
    /** The data saver's answer for a big download (false = refused right now). */
    private val fetchAllowed: () -> Boolean = { true },
) {
    fun testPlaces(): List<UserPlace> = pinning.pins().filter { it.note == NOTE }

    /** A test place at the current position (a fresh fix only, the normal pin rules and pause zones apply). Returns the line to show. */
    suspend fun placeHere(name: String): String {
        pinning.begin().onFailure { return refusal(it) }
        return saved(pinning.create(form(name)))
    }

    /** A test place at typed coordinates. */
    suspend fun placeAt(name: String, latText: String, lonText: String): String {
        val lat = latText.trim().toDoubleOrNull(); val lon = lonText.trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) return "Enter a latitude (-90 to 90) and a longitude (-180 to 180)."
        return saved(pinning.createAt(lat, lon, form(name)))
    }

    /** Adds [bytes] as an imported photo and attaches its Moment to the test place [pinId]. */
    suspend fun addImage(pinId: String, bytes: ByteArray): String {
        if (testPlaces().none { it.id == pinId }) return "Choose a test place first."
        val at = ingest(bytes) ?: return "The image was not stored (not a readable image, or that second was forgotten)."
        val moment = try { MomentId.ofMs(at) } catch (e: IllegalArgumentException) { return "The image was not stored." }
        return if (pinning.setMoment(pinId, moment)) "Imported photo attached to the test place." else "The photo was stored but could not be attached to the place."
    }

    suspend fun addImageFromUrl(pinId: String, url: String): String {
        if (DevImageFetch.validUrl(url) == null) return "Only https links are accepted."
        if (!fetchAllowed()) return "The data saver or Offline only refuses a download right now."
        val bytes = fetch(url).getOrElse { return "Download failed: ${it.message ?: "error"}." }
        return addImage(pinId, bytes)
    }

    private fun form(name: String) = PinForm(testName(name), PlaceKinds.ATTRACTION, UserPlace.DEFAULT_RADIUS_M, null, NOTE, null)

    private fun saved(r: PinResult): String = when (r) {
        is PinResult.Saved -> "Test place added: ${r.place.name}."
        is PinResult.Refused -> refusalText(r.why)
    }

    private fun refusal(t: Throwable): String = (t as? PlacePinning.PinRefused)?.let { refusalText(it.why) } ?: "The place was not added."

    private fun refusalText(why: PinRefusal): String = when (why) {
        PinRefusal.ZONE -> "Refused: a pause zone is active or near."
        PinRefusal.NO_FRESH_FIX -> "No fresh, accurate location yet. Try again outdoors."
        PinRefusal.INVALID -> "The place is not valid (or the pin list is full)."
        else -> "The place was not added."
    }

    companion object {
        /** The note that marks a test place (a pin note is the card text, so a test place's card says so). */
        const val NOTE = "Developer test place"
        const val PREFIX = "Test: "
        fun testName(typed: String): String = (PREFIX + UserPlace.cleanName(typed).ifBlank { "place" }).take(UserPlace.NAME_MAX)

        /** The real wiring: the photo goes through [PhoneCapture] (scale, write whole, no worker) and then through the normal frame ingest with the imported source. */
        fun create(app: Context, pinning: PlacePinning): DevTestImport {
            val capture = PhoneCapture(app, enqueue = { _, _ -> })
            return DevTestImport(
                pinning,
                ingest = { bytes -> ingestBytes(app, capture, bytes) },
                fetch = { url -> DevImageFetch.fetch(Http.modelClient(DevImageFetch.TIMEOUT_SEC), url) },
                fetchAllowed = { runCatching { com.rokid.context.ai.datasaver.DataSaverGraph.get(app).decide(DataKind.BULK_OTHER).allowed }.getOrDefault(false) },
            )
        }

        private suspend fun ingestBytes(app: Context, capture: PhoneCapture, bytes: ByteArray): Long? = withContext(Dispatchers.IO) {
            val tmp = File(File(app.cacheDir, PhoneCapture.DIR).apply { mkdirs() }, "dev_import_${System.currentTimeMillis()}.jpg")
            try {
                tmp.writeBytes(bytes)
                val out = capture.ingestTo(tmp, FrameSources.DEV_IMPORT) ?: return@withContext null
                val id = RokidFrameInbox.ingestSinglePath(app, out.path, FrameSources.DEV_IMPORT) ?: run { out.delete(); return@withContext null }
                KeyframeGraph.get(app).signal()
                MemoryGraph.get(app).frames.byIds(listOf(id)).firstOrNull()?.ts
            } catch (e: Exception) {
                PcLog.w("DevTestImport", "import failed: ${e.javaClass.simpleName}"); null
            } finally { tmp.delete() }
        }
    }
}

/** WP8: the capped https download of a test photo, through the app's one OkHttp builder ([Http], no redirects, the Offline only guards). */
object DevImageFetch {
    const val MAX_BYTES = 10L * 1024 * 1024
    const val TIMEOUT_SEC = 20L

    /** The URL when it is a well-formed https link, else null (http, file, content and everything else is refused). */
    fun validUrl(s: String): HttpUrl? = s.trim().toHttpUrlOrNull()?.takeIf { it.isHttps }

    /** Reads at most [max] bytes; more than that is an error (never a truncated image). */
    fun readCapped(input: InputStream, max: Long = MAX_BYTES): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("larger than ${max / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun fetch(client: OkHttpClient, url: String, max: Long = MAX_BYTES): Result<ByteArray> = runCatching {
        val u = validUrl(url) ?: throw IOException("only https links are accepted")
        client.newCall(Request.Builder().url(u).header("Accept", "image/*").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty answer")
            if (body.contentLength() > max) throw IOException("larger than ${max / (1024 * 1024)} MB")
            readCapped(body.byteStream(), max)
        }
    }
}

/** The Developer screen section: "Add place here", "Add place at lat/lon", "Add image" from a link or the photo picker. */
@Composable
fun DevTestPlaceSection(control: DevTestImport) {
    val scope = rememberCoroutineScope()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var places by remember { mutableStateOf(control.testPlaces()) }
    var selected by remember { mutableIntStateOf(0) }
    val target = places.getOrNull(selected)?.id

    fun run(block: suspend () -> String) { if (busy) return; busy = true; scope.launch { message = try { block() } finally { places = control.testPlaces(); busy = false } } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = target
        if (uri != null && id != null) run {
            withContext(Dispatchers.IO) {
                                val bytes = try { app.contentResolver.openInputStream(uri)?.use { DevImageFetch.readCapped(it) } } catch (e: Exception) { null }
                if (bytes == null) "The image could not be read (or it is larger than 10 MB)." else control.addImage(id, bytes)
            }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("dev_test_place"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Test place and test photos", style = MaterialTheme.typography.titleSmall)
        Text("Make a place pin and attach a photo to it, to try place cards at an ordinary shop. Imported photos are marked as imported and never go into the memory export.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dev_place_name"))
        PrimaryAction("Add place here", { run { control.placeHere(name) } }, "dev_place_here", enabled = !busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(lat, { lat = it }, label = { Text("Lat") }, singleLine = true, modifier = Modifier.weight(1f).testTag("dev_place_lat"))
            OutlinedTextField(lon, { lon = it }, label = { Text("Lon") }, singleLine = true, modifier = Modifier.weight(1f).testTag("dev_place_lon"))
        }
        SecondaryAction("Add place at lat/lon", { run { control.placeAt(name, lat, lon) } }, "dev_place_at", enabled = !busy)
        if (places.isNotEmpty()) {
            Text("Attach photos to:", style = MaterialTheme.typography.labelMedium)
            places.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = i == selected, onClick = { selected = i })
                    Text(p.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(url, { url = it }, label = { Text("Image link (https)") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("dev_image_url"))
            SecondaryAction("Add image from link", { target?.let { id -> run { control.addImageFromUrl(id, url) } } }, "dev_image_from_url", enabled = !busy && target != null)
            SecondaryAction("Add image from photos", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, "dev_image_picker", enabled = !busy && target != null)
        } else Text("Add a test place first, then you can attach images.", style = MaterialTheme.typography.bodySmall, color = PcColors.InkSoft)
        message?.let { NoticeText(it, tag = "dev_test_place_message") }
    }
}
