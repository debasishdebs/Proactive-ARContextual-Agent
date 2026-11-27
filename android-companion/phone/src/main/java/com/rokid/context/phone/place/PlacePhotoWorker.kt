package com.rokid.context.phone.place

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.rokid.context.connectors.place.PhotoEnd
import com.rokid.context.connectors.place.PhotoPhaseRetry
import com.rokid.context.connectors.place.PlaceImageFetcher
import com.rokid.context.connectors.place.PlaceImagePhase
import com.rokid.context.connectors.place.PlaceImageRules
import com.rokid.context.connectors.place.Thumb
import com.rokid.context.connectors.place.ThumbCodec
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.memory.place.PlaceOfflineOnly
import com.rokid.context.memory.place.SeedAdmission
import com.rokid.context.memory.place.SeedStopped
import com.rokid.context.orchestration.forget.DeveloperFlags
import com.rokid.context.work.frames.place.EmbedEnd
import com.rokid.context.work.frames.place.PlaceGraph
import com.rokid.context.work.frames.place.PlaceRefPhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * B2 photo admission (owner D3): reference photos use FREE Wi-Fi only, whatever the global Wi-Fi-only setting says, never under Offline only, and the shared data saver (Android Data Saver, daily limit) still decides. Asked before every
 * request. Returns null = go on, else a short fixed reason. Fails closed.
 */
object PhotoGate {
    const val OFF = "photos off"
    fun check(privacy: () -> ModelPrivacy, cost: () -> NetworkCost, gate: DataGate, on: () -> Boolean = { true }): String? = try {
        when {
            !on() -> OFF
            privacy() != ModelPrivacy.AUTO -> SeedGate.OFFLINE_ONLY
            else -> when (cost()) {
                NetworkCost.FREE -> if (gate.decide(DataKind.BULK_OTHER).allowed) null else SeedGate.DATA_SAVER
                NetworkCost.OFFLINE -> SeedGate.NO_NETWORK
                else -> SeedGate.NOT_FREE_WIFI // mobile, roaming, unknown or restricted: never for photos
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { SeedGate.UNREADABLE }

    fun admission(app: Context): () -> String? {
        val saver = com.rokid.context.ai.datasaver.DataSaverGraph.get(app)
        return { check({ com.rokid.context.ai.resolve.ModelPrivacyStore.get(app).current() }, saver::costNow, saver, { DeveloperFlags.placeRefPhotos(app) }) }
    }
}

/** What the photo phase is doing, for the Place Guide line. Persisted (process death keeps it); counts of places come from the pack. */
enum class PhotoPhase { IDLE, RUNNING, WAITING_WIFI, OFFLINE_ONLY, CAP, FAILED, DONE, OFF }

class PhotoPhaseStore(app: Context) {
    private val p = app.getSharedPreferences("place_photo_phase", Context.MODE_PRIVATE)
    fun get(): PhotoPhase = runCatching { PhotoPhase.valueOf(p.getString("state", null) ?: "IDLE") }.getOrDefault(PhotoPhase.IDLE)
    fun set(s: PhotoPhase) { p.edit().putString("state", s.name).apply() }
    fun clear() { p.edit().clear().apply() }
}

/** The Android decode check and re-encode: any JPEG, PNG or WebP is decoded (bounds first, so a huge picture is refused), scaled to at most 336 px and written as a JPEG at quality 80 (which also drops every metadata block). */
object AndroidThumbCodec : ThumbCodec {
    override fun normalize(raw: ByteArray): Thumb? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth.toLong() * o.outHeight > 16_000_000L) return null
        var bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        try {
            val side = maxOf(bmp.width, bmp.height)
            if (side > PlaceImageRules.MAX_SIDE) {
                val k = PlaceImageRules.MAX_SIDE.toFloat() / side
                val s = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                if (s !== bmp) { bmp.recycle(); bmp = s }
            }
            val out = java.io.ByteArrayOutputStream()
            if (!bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)) return null
            return Thumb(out.toByteArray(), bmp.width, bmp.height)
        } finally { bmp.recycle() }
    }
}

/**
 * The photo phase as background work (B2): ONE unique work for the whole pack, so it fills the places of a seed that just finished AND every place of areas seeded before this build (catch-up: the pack's queue is "seeded place without a photo row").
 * Constraint UNMETERED (free Wi-Fi) and the same rule asked again before every request ([PhotoGate]). Resumable by construction: every decided place is a row. Counts only in logs.
 */
class PlacePhotoWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val store by lazy { PhotoPhaseStore(applicationContext) }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(0, 0)

    override suspend fun doWork(): Result {
        if (!DeveloperFlags.placeRefPhotos(applicationContext)) { store.set(PhotoPhase.OFF); return Result.success() }
        val admit = PhotoGate.admission(applicationContext)
        admit()?.let { return stopped(it) }
        val space = PlaceRefPhotos.activeSpace(applicationContext) ?: "-"
        if (withContext(Dispatchers.IO) { packOf(applicationContext).needsFetch(space, PlaceImageFetcher.MAX_TRIES, 1) }.isEmpty()) { store.set(PhotoPhase.DONE); PlacePhotoWork(applicationContext).ensureEmbed(); return Result.success() } // nothing missing: no notification
        try { setForeground(info(0, 0)) } catch (e: Exception) { PcLog.i(TAG, "photo work: no foreground (${e.javaClass.simpleName})") }
        store.set(PhotoPhase.RUNNING)
        PcLog.i(TAG, "photo work run attempt=${runAttemptCount + 1}")
        return try {
            val pack = packOf(applicationContext)
            val phase = PlaceImagePhase(pack, PlaceImageFetcher(apiOf(applicationContext), AndroidThumbCodec, concurrency = concurrency), { PlaceRefPhotos.activeSpace(applicationContext) ?: "-" })
            val end = withContext(Dispatchers.IO + SeedAdmission(admit)) {
                phase.run { t -> runCatching { PlacePhotoWork(applicationContext).ensureEmbed() }; runCatching { setProgress(Data.Builder().putInt("ok", t.ok).putInt("of", t.seeded).build()); setForeground(info(t.ok + t.none, t.seeded)) } }
            }
            val t = packOf(applicationContext).photoTotals()
            val how = end.name; val n = t.seeded; val ok = t.ok; val failed = t.none; val left = t.waiting
            PcLog.i(TAG, "photo work done how=$how n=$n ok=$ok none=$failed left=$left")
            store.set(if (end == PhotoEnd.CAP_REACHED) PhotoPhase.CAP else PhotoPhase.DONE)
            PlacePhotoWork(applicationContext).ensureEmbed()
            Result.success()
        } catch (e: SeedStopped) { stopped(e.reason)
        } catch (e: PlaceOfflineOnly) { stopped(SeedGate.OFFLINE_ONLY)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: PhotoPhaseRetry) {
            val cls = e.cause?.javaClass?.simpleName ?: "none"
            PcLog.i(TAG, "photo work retry cls=$cls")
            if (runAttemptCount >= MAX_ATTEMPTS) { store.set(PhotoPhase.FAILED); Result.failure() } else Result.retry()
        } catch (e: Exception) {
            PcLog.i(TAG, "photo work failed cls=${e.javaClass.simpleName}"); store.set(PhotoPhase.FAILED); Result.failure()
        }
    }

    private fun stopped(reason: String): Result = when (reason) {
        SeedGate.OFFLINE_ONLY -> { store.set(PhotoPhase.OFFLINE_ONLY); PcLog.i(TAG, "photo work refused: offline only"); Result.failure() }
        PhotoGate.OFF -> { store.set(PhotoPhase.OFF); Result.success() }
        else -> { store.set(PhotoPhase.WAITING_WIFI); val why = reason.replace(' ', '_'); PcLog.i(TAG, "photo work waits why=$why"); Result.retry() } // free Wi-Fi, a network or the data saver: wait, never mobile data
    }

    private fun info(done: Int, of: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Place photos", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Preparing place photos").setContentText(if (of > 0) "$done of $of places" else null).setProgress(of.coerceAtLeast(1), done.coerceIn(0, of.coerceAtLeast(1)), of == 0)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(android.R.drawable.ic_delete, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE = "place_photos"
        const val MAX_ATTEMPTS = 8
        private const val TAG = "PcPlacePhotos"
        /** One per process: after a 429/503 the next run (WorkManager retry) still starts with a single connection. */
        private val concurrency = com.rokid.context.connectors.place.DownloadConcurrency()
        private const val CHANNEL = "place_photos"
        private const val NOTIFICATION_ID = 7312
        @androidx.annotation.VisibleForTesting internal var packOf: suspend (Context) -> com.rokid.context.memory.place.PlacePack = { PlaceGraph.readyPack(it) }
        @androidx.annotation.VisibleForTesting internal var apiOf: (Context) -> com.rokid.context.connectors.place.PlaceImageApi = { PlaceGraph.photoApi(it) }
    }
}

/** Embeds ("matching") the stored thumbnails right after they are downloaded (B2, owner decision): no charging rule; it stops only when the phone is thermally MODERATE or hotter and resumes later. */
class PlaceEmbedWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val pack = PlaceGraph.readyPack(applicationContext)
        val e = PlaceRefPhotos.embedder(applicationContext, pack)
        PcLog.i(TAG, "photo embed start attempt=${runAttemptCount + 1}")
        val end = withContext(Dispatchers.Default) { e.run() }
        val how = end.name; val ok = e.embedded; val failed = e.failed
        PcLog.i(TAG, "photo embed how=$how ok=$ok failed=$failed")
        // A hot phone is asked again with backoff; a model not ready yet is retried a few times (the app start queues it again too). A finished run may have freed budget for the fetch.
        if (end == EmbedEnd.DONE) runCatching { PlacePhotoWork(applicationContext).ensureIfNeeded(pack) }
        return when (end) { EmbedEnd.HOT -> Result.retry(); EmbedEnd.NO_SPACE -> if (runAttemptCount < MAX_WAITS) Result.retry() else Result.success(); else -> Result.success() }
    }
    companion object { /** The v2 name: an older build left a CHARGING-only work under [LEGACY_UNIQUE] that KEEP kept for ever (it never ran), so the matching never started. */ const val UNIQUE = "place_photo_embed_v2"; const val LEGACY_UNIQUE = "place_photo_embed"; const val MAX_WAITS = 6; private const val TAG = "PcPlacePhotos" }
}

/** Queues and cancels the two photo works (fetch, then matching). Every call is cheap and idempotent (KEEP). */
class PlacePhotoWork(private val app: Context, private val wm: () -> WorkManager = { WorkManager.getInstance(app) }) {
    /** Photo phase: queued after a seed, at app start (catch-up for areas seeded earlier) and by Retry. Free Wi-Fi only (the constraint; the gate asks again per request). */
    fun ensure(replace: Boolean = false) {
        if (!DeveloperFlags.placeRefPhotos(app)) { PhotoPhaseStore(app).set(PhotoPhase.OFF); return }
        val req = OneTimeWorkRequestBuilder<PlacePhotoWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(PlaceSeedWorker.TAG_ALL).build()
        wm().enqueueUniqueWork(PlacePhotoWorker.UNIQUE, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req)
    }

    /** App start: queues the phase only while some seeded place still has no photo row (so a start with nothing to do shows nothing), and the embed work for fetched photos without a vector. */
    suspend fun ensureIfNeeded(pack: com.rokid.context.memory.place.PlacePack) {
        val space = PlaceRefPhotos.activeSpace(app) ?: "-"
        if (pack.needsFetch(space, PlaceImageFetcher.MAX_TRIES, 1).isNotEmpty()) ensure()
        if (pack.needsEmbed(space, 1).isNotEmpty()) ensureEmbed()
    }

    /** The "Retry" button: starts the phase again (failed places with tries left included). */
    fun retry() = ensure(replace = true)

    /** Embed ("matching"): queued as soon as photos are stored; no charging or network constraint (the worker stops itself when the phone is too hot). */
    fun ensureEmbed() {
        if (!DeveloperFlags.placeRefPhotos(app)) return
        val req = OneTimeWorkRequestBuilder<PlaceEmbedWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES).build()
        wm().cancelUniqueWork(PlaceEmbedWorker.LEGACY_UNIQUE) // idempotent: removes a stale charging-only work of an older build
        wm().enqueueUniqueWork(PlaceEmbedWorker.UNIQUE, ExistingWorkPolicy.KEEP, req)
    }

    /** Counts only: (photos fetched, of those already embedded in the active space). */
    suspend fun embedCounts(): Pair<Int, Int> {
        val pack = PlaceGraph.readyPack(app)
        val fetched = pack.imageStats().ok
        val space = PlaceRefPhotos.activeSpace(app) ?: return fetched to 0
        return fetched to (fetched - pack.needsEmbed(space, 100_000).size).coerceAtLeast(0)
    }

    fun cancelAll() { wm().cancelUniqueWork(PlacePhotoWorker.UNIQUE); wm().cancelUniqueWork(PlaceEmbedWorker.UNIQUE); wm().cancelUniqueWork(PlaceEmbedWorker.LEGACY_UNIQUE); PhotoPhaseStore(app).clear() }
}

/** The words of the photo phase for the lists (fixed phrases, no names). */
object PlacePhotoText {
    /** Why the photos of an incomplete area wait; null while the phase is running or done. */
    fun note(s: PhotoPhase): String? = when (s) {
        PhotoPhase.RUNNING, PhotoPhase.DONE -> null
        PhotoPhase.OFFLINE_ONLY -> "Offline only"
        PhotoPhase.OFF -> "photos are off"
        PhotoPhase.FAILED -> "failed, tap Retry photos"
        PhotoPhase.CAP -> "photo storage is full"
        PhotoPhase.IDLE, PhotoPhase.WAITING_WIFI -> "waiting for free Wi-Fi"
    }
}

/** The ONE overall progress of an area's photos, in words (counts only): "Places", "Photos x of y", "Matching x of y". */
object PhotoStageText {
    /** [placesLive]: the area's places are still being seeded. [matched]/[toMatch]: downloaded photos already matched, of all downloaded photos (whole phone). [note]: why photos wait, null while they run. */
    fun line(placesLive: Boolean, done: Int, total: Int, matched: Int, toMatch: Int, note: String?): String? = when {
        placesLive -> "Places"
        total <= 0 -> null
        done < total -> "Photos $done of $total" + (note?.let { " ($it)" } ?: "")
        toMatch > 0 && matched < toMatch -> "Matching $matched of $toMatch"
        else -> null
    }
}
