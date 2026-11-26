package com.rokid.context.phone.place

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import com.rokid.context.core.log.PcLog
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.SeedStopped
import com.rokid.context.work.frames.place.PlaceGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Seeds one area in the background (S3). The work's only data is the area's hash key (never the box or a name); the box is read from the pack's area row, written before the work is queued.
 * Per-cell work, the admission before every cell and request, the zone guard and resume (fresh cells are skipped) are all [PlacePack.seedArea]'s. This class only schedules, reports progress and maps a stop to a work result.
 * Logs carry counts and a fixed reason only.
 */
class PlaceSeedWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val store by lazy { SeedProgressStore(applicationContext) }
    private val timing by lazy { SeedTimingStore(applicationContext) }
    private val key: String get() = inputData.getString(KEY).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = info(store.get(key).percent)

    override suspend fun doWork(): Result {
        val pack = packOf(applicationContext)
        val area = pack.areas().firstOrNull { SeedKeys.of(it.blueZoneId) == key } ?: return Result.success().also { store.clear(key); PcLog.i(TAG, "seed work: area gone") }
        val admit = admission(applicationContext)
        admit()?.let { return stopped(it) }
        try { setForeground(info(store.get(key).percent)) } catch (e: Exception) { PcLog.i(TAG, "seed work: no foreground (${e.javaClass.simpleName})") } // a refused foreground start must not fail the seed
        store.setWaiting(key, false)
        return try {
            // One area at a time (Wikipedia's rate limit): a seed that starts while another runs waits here, first come first served, and reads "queued" meanwhile.
            store.setQueued(key, true)
            SeedQueue.gate.withLock {
                store.setQueued(key, false)
                runSeed(pack, area.blueZoneId, area.box, admit)
            }
        } catch (e: SeedStopped) {
            stopped(e.reason)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure that is not a stop never leaves the area "seeding": it ends partial and the owner can retry (the log has the class only).
            PcLog.i(TAG, "seed work failed cls=${e.javaClass.simpleName}")
            runCatching { pack.stallSeeding(area.blueZoneId, graceMs = 0L) }
            store.setQueued(key, false); store.setWaiting(key, false)
            Result.failure()
        } finally { store.setQueued(key, false) }
    }

    private suspend fun runSeed(pack: PlacePack, id: String, box: com.rokid.context.memory.place.GeoBox, admit: () -> String?): Result {
        val updates = kotlinx.coroutines.channels.Channel<Int>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val r = kotlinx.coroutines.coroutineScope {
            // the notification and the work progress follow the cell progress; a conflated channel keeps the cell loop from ever waiting on them
            val reporter = launch { for (pct in updates) { try { setProgress(workDataOf(PROGRESS to pct)); setForeground(info(pct)) } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e } } }
            try {
                withContext(Dispatchers.IO) { pack.seedArea(id, box, admit = admit, onProgress = { done, total -> store.progress(key, done, total); updates.trySend(store.get(key).percent) }, onCellMs = { ms -> timing.add(ms) }) }
            } finally { updates.close(); reporter.join() }
        }
        PcLog.i(TAG, "seed work done failed=${r.failed} partial=${r.partial}")
        if (r.failed == 0 && r.partial == 0) store.clear(key) // resumed seeds that ended partial keep their counts for the next try
        else store.setFailed(key, r.failed + r.partial) // the FAILED state shows this count and "Retry failed cells" re-queues exactly these
        return Result.success()
    }

    /** A stop at the admission: no network or a closed data saver waits and retries with backoff; Offline only fails; a removed area ends quietly. */
    private fun stopped(reason: String): Result = when (reason) {
        SeedGate.OFFLINE_ONLY -> { PcLog.i(TAG, "seed work refused: offline only"); Result.failure() }
        REMOVED -> { store.clear(key); Result.success() }
        else -> if (runAttemptCount >= MAX_WORKER_ATTEMPTS) {
            // give up after the cap: the area ends partial (never "seeding" forever) and "Retry failed cells" / Seed again restarts it
            PcLog.i(TAG, "seed work gave up after $runAttemptCount attempts")
            store.setWaiting(key, false)
            Result.failure()
        } else { store.setWaiting(key, true); PcLog.i(TAG, "seed work waits"); Result.retry() }
    }

    private fun info(pct: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Place seeding", NotificationManager.IMPORTANCE_LOW))
        return ForegroundInfo(NOTIFICATION_ID, notification(applicationContext, pct, id), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY = "area_key"
        const val PROGRESS = "pct"
        const val TAG_ALL = "place_seed"
        const val REMOVED = "area removed"
        /** Waits for a network or the data saver with growing backoff; after this many tries the seed gives up (the work constraint itself waits for a network without running). */
        const val MAX_WORKER_ATTEMPTS = 8
        private const val TAG = "PcPlaceSeed"
        private const val CHANNEL = "place_seed"
        private const val NOTIFICATION_ID = 7311

        /** The notification: only "Seeding places NN%" and a Cancel action. No place name, no position. */
        fun notification(ctx: Context, pct: Int, workId: java.util.UUID): Notification =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Seeding places $pct%")
                .setProgress(100, pct.coerceIn(0, 100), false)
                .setOngoing(true).setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_delete, "Cancel", WorkManager.getInstance(ctx).createCancelPendingIntent(workId))
                .build()

        /** The process-wide pack (tests replace it). */
        @androidx.annotation.VisibleForTesting internal var packOf: suspend (Context) -> PlacePack = { PlaceGraph.readyPack(it) }

        /** The seed's admission as the pack asks it before every cell and request (tests replace it). */
        @androidx.annotation.VisibleForTesting internal var admission: (Context) -> (() -> String?) = ::seedAdmission
    }
}

/** One seed at a time in this process (the public APIs rate-limit; presets with several boxes run one after another). Mutex waiters are served in order. */
object SeedQueue { val gate = Mutex() }

/** Queues, cancels and reads the background seeds. One unique work per area (KEEP), expedited, network constraint by the global Wi-Fi-only setting. */
class SeedWork(private val app: Context, private val wifiOnly: () -> Boolean = { com.rokid.context.ai.datasaver.DataSaverGraph.get(app).settingsNow().mode == com.rokid.context.core.net.DataMode.WIFI_ONLY },
               private val wm: () -> WorkManager = { WorkManager.getInstance(app) }) {
    private val store = SeedProgressStore(app)

    fun uniqueName(areaId: String) = "place_seed_" + SeedKeys.of(areaId)

    /** The network rule at queue time: CONNECTED, or UNMETERED while the global setting is ON. */
    fun constraints(): Constraints = Constraints.Builder().setRequiredNetworkType(SeedNetwork.requirement(if (wifiOnly()) com.rokid.context.core.net.DataMode.WIFI_ONLY else com.rokid.context.core.net.DataMode.DEFAULT)).build()

    fun enqueue(areaId: String) {
        val key = SeedKeys.of(areaId)
        store.begin(key)
        val req = OneTimeWorkRequestBuilder<PlaceSeedWorker>()
            .setInputData(Data.Builder().putString(PlaceSeedWorker.KEY, key).build())
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(PlaceSeedWorker.TAG_ALL)
            .build()
        wm().enqueueUniqueWork(uniqueName(areaId), ExistingWorkPolicy.KEEP, req)
    }

    fun cancel(areaId: String) { store.markCancelled(SeedKeys.of(areaId)); wm().cancelUniqueWork(uniqueName(areaId)) }

    /** Delete all and the like: every seed work goes, the progress with it. A seeding area left behind is reconciled to partial. */
    fun cancelAll() { wm().cancelAllWorkByTag(PlaceSeedWorker.TAG_ALL); store.clearAll() }

    private suspend fun stateOf(areaId: String): WorkInfo.State? = withContext(Dispatchers.IO) {
        wm().getWorkInfosForUniqueWorkFlow(uniqueName(areaId)).first().maxByOrNull { if (SeedStates.isLive(it.state)) 1 else 0 }?.state
    }

    /** The derived state of every area that has a seed row (or progress), keyed by area id. */
    suspend fun states(pack: PlacePack, blocked: Boolean): Map<String, SeedAreaState> =
        pack.areas().associate { a -> a.blueZoneId to SeedStates.derive(stateOf(a.blueZoneId), store.get(SeedKeys.of(a.blueZoneId)), a.status, blocked) }

    /** App start and every screen read: an area marked seeding with no live work becomes partial, so it can never stay "(seeding)". Returns how many were changed. */
    suspend fun reconcile(pack: PlacePack, graceMs: Long = 30_000L): Int {
        val areas = pack.areas()
        val live = areas.map { it.blueZoneId }.filter { SeedStates.isLive(stateOf(it)) }.toSet()
        var n = 0
        for (id in SeedStates.stalled(areas.map { it.blueZoneId to it.status }, live)) if (pack.stallSeeding(id, graceMs)) { n++; store.setWaiting(SeedKeys.of(id), false) }
        return n
    }
}

/** Delete all: the queued and running seeds are cancelled with the rest of the user's data. */
class SeedWipeListener(private val app: Context) : com.rokid.context.memory.forget.ForgetListener {
    override suspend fun onDeleteAll() { SeedWork(app).cancelAll() }
}
