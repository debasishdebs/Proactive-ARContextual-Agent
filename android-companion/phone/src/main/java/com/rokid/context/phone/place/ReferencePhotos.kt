package com.rokid.context.phone.place

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import com.rokid.context.canvas.dumpimport.ImportFrameStore
import com.rokid.context.canvas.dumpimport.ImportMemoryStore
import com.rokid.context.canvas.dumpimport.ReferenceMoment
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.work.frames.place.HopResponse
import com.rokid.context.work.frames.place.ImageFetcher
import com.rokid.context.work.frames.place.ImageHop
import com.rokid.context.work.frames.place.PhotoFault
import com.rokid.context.work.frames.place.PreparedPhoto
import com.rokid.context.work.frames.place.RawPhoto
import com.rokid.context.work.frames.place.RefPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** One answer of the reference photo steps: a photo ready to store, or a fixed reason (never a link, a host or a byte). */
sealed interface PhotoResult {
    class Ok(val photo: PreparedPhoto) : PhotoResult
    class Fail(val fault: PhotoFault) : PhotoResult
}

/**
 * Developer reference photo: the phone side of "Add place manually > Photo". The link goes through the safe downloader ([ImageFetcher]: https only, every resolved address public, 3 redirects, 12 MB streamed cap, magic bytes), the
 * file picker's stream through the same size cap and checks, then BOTH are decoded once (bounds first, 8000 px / 48 MP cap), turned upright by their orientation tag, scaled to at most [STORE_SIDE] px and re-encoded as a JPEG
 * (which drops every metadata block, a camera's GPS included). The Moment is made by [ReferenceMoment] over the import writers. Nothing is logged here.
 */
class ReferencePhotos(private val app: Context) {
    /** The downloader's single egress: a request to the addresses [ImageFetcher] already checked, no redirect following, no cookies, no proxy, the identifying User-Agent only. */
    private class OkHop : ImageHop {
        override fun get(url: String, addrs: List<InetAddress>): HopResponse {
            val client = OkHttpClient.Builder()
                .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = addrs })
                .followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES).retryOnConnectionFailure(false)
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
            val resp = client.newCall(Request.Builder().url(url).header("User-Agent", ImageFetcher.USER_AGENT).build()).execute()
            val body = resp.body
            return HopResponse(resp.code, resp.header("Location"), body?.contentLength() ?: -1L, body?.byteStream()) { resp.close() }
        }
    }

    private fun admission(): PhotoFault? {
        val why = seedAdmission(app)() ?: return null
        return when (why) {
            SeedGate.OFFLINE_ONLY -> PhotoFault.OFFLINE_ONLY
            SeedGate.NO_NETWORK -> PhotoFault.NO_NETWORK
            SeedGate.NOT_FREE_WIFI -> PhotoFault.NOT_FREE_WIFI
            else -> PhotoFault.DATA_SAVER
        }
    }

    /** Fetches [link]; a bad link or any refusal is a [PhotoFault]. */
    suspend fun fromLink(link: String): PhotoResult = withContext(Dispatchers.IO) {
        val gate = com.rokid.context.ai.datasaver.DataSaverGraph.get(app)
        val costly = try { gate.costlyNow() } catch (e: Exception) { true }
        val raw = ImageFetcher({ InetAddress.getAllByName(it).toList() }, OkHop(), ::admission).fetch(link)
        raw.bytes?.let { try { gate.sentAt(com.rokid.context.core.net.DataKind.BULK_OTHER, 0L, it.size.toLong(), 0, costly) } catch (e: Exception) { /* counting never breaks the form */ } }
        prepare(raw)
    }

    /** Reads the document the picker returned (no storage permission), with the same cap, checks and re-encode. */
    suspend fun fromUri(uri: String): PhotoResult = withContext(Dispatchers.IO) {
        val bytes = try { app.contentResolver.openInputStream(Uri.parse(uri))?.use { RefPhoto.readCapped(it) } ?: return@withContext PhotoResult.Fail(PhotoFault.TOO_BIG) } catch (e: Exception) { return@withContext PhotoResult.Fail(PhotoFault.UNREADABLE) }
        prepare(RawPhoto.ok(bytes))
    }

    private fun prepare(raw: RawPhoto): PhotoResult {
        val bytes = raw.bytes ?: return PhotoResult.Fail(raw.fault ?: PhotoFault.FAILED)
        RefPhoto.validate(bytes, ::bounds)?.let { return PhotoResult.Fail(it) }
        return reencode(bytes)?.let { PhotoResult.Ok(it) } ?: PhotoResult.Fail(PhotoFault.UNREADABLE)
    }

    private fun bounds(b: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    private fun reencode(b: ByteArray): PreparedPhoto? = try {
        val (w, h) = bounds(b) ?: return null
        var sample = 1
        while (maxOf(w, h) / sample > STORE_SIDE * 2) sample *= 2
        val raw = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turn = when (try { android.media.ExifInterface(java.io.ByteArrayInputStream(b)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } catch (e: Exception) { 1 }) { 3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f }
        val scale = minOf(1f, STORE_SIDE.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { if (turn != 0f) postRotate(turn); if (scale < 1f) postScale(scale, scale) }
        val bmp = if (turn == 0f && scale >= 1f) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val out = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        val pw = bmp.width; val ph = bmp.height
        if (bmp !== raw) bmp.recycle(); raw.recycle()
        if (ok) PreparedPhoto(out.toByteArray(), pw, ph) else null
    } catch (e: Throwable) { null }

    private fun maker(): ReferenceMoment {
        val db = MemoryGraph.get(app).db
        return ReferenceMoment(
            db,
            frames = ImportFrameStore(app, { f, bytes, t -> com.rokid.context.phone.hub.AtomicJpegWriter.writeStamped(f, bytes, t) }),
            memories = ImportMemoryStore(app),
            build = { ids -> com.rokid.context.canvas.moment.MomentTriggers.builder(app).build(ids) },
            generation = { MemoryWipe.generation.get() },
            discard = { frameId -> com.rokid.context.orchestration.forget.ForgetActions.forgetMoment(app, frameId) },
        )
    }

    /** The reference Moment of [photo] (no location, time now, memory = [description]) and the pin's link to it. False = nothing is left behind (the pin stays, without a link). */
    suspend fun addTo(pinning: com.rokid.context.work.frames.place.PlacePinning, pinId: String, photo: PreparedPhoto, description: String): Boolean = withContext(Dispatchers.IO) {
        val made = maker().create(photo.jpeg, description) ?: return@withContext false
        if (pinning.setMoment(pinId, made.momentId)) return@withContext true
        try { com.rokid.context.orchestration.forget.ForgetActions.forgetMoment(app, made.frameId) } catch (e: Exception) { /* the Moment stays as an ordinary photo Moment */ }
        false
    }

    /** For each Moment id: true = its photo has an image vector (indexed), false = still waiting. */
    suspend fun indexed(momentIds: List<String>): Map<String, Boolean> {
        val db = MemoryGraph.get(app).db
        return momentIds.distinct().mapNotNull { id ->
            val f = db.momentDao().photoFrameOf(id) ?: return@mapNotNull null
            id to (db.embeddingDao().imageVectorCountOf(f.id) > 0)
        }.toMap()
    }

    companion object { const val STORE_SIDE = 2048 }
}
