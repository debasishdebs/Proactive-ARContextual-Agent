package com.rokid.context.phone.place

import android.content.Context
import androidx.work.WorkInfo
import com.rokid.context.core.net.DataMode
import com.rokid.context.memory.place.AREA_DONE
import com.rokid.context.memory.place.AREA_SEEDING
import com.rokid.context.memory.place.SeedTally
import java.security.MessageDigest

/** The state of one seed area's background seed, derived from the work state plus the persisted cell progress (S3). Never carries a name or a position. */
enum class SeedPhase { QUEUED, RUNNING, WAITING_NETWORK, DONE, FAILED, CANCELLED }

data class SeedAreaState(val phase: SeedPhase, val percent: Int = 0, /** FAILED only: cells that stored nothing (or no OSM extras) after the retries; the "Retry failed cells" button re-queues exactly these. */ val failedCells: Int = 0, /** The persisted cell tally (counts only); null = unknown, the text then shows the plain percent. */ val counts: SeedProgress? = null) {
    /** Still to finish (the row shows a progress line and a Cancel button). */
    val active: Boolean get() = phase == SeedPhase.QUEUED || phase == SeedPhase.RUNNING || phase == SeedPhase.WAITING_NETWORK

    /** "25% done (525 of 2100) · 38% ok (200) · 62% failed (325) · 12 partial" (see [SeedTally]), or the plain "NN%" while no total is known. */
    private val detail: String get() = counts?.takeIf { it.total > 0 }?.detail() ?: "$percent%"

    /** The one line the screens and the notification use: no place name, no position. */
    val text: String get() = when (phase) {
        SeedPhase.QUEUED -> "Seeding queued"
        SeedPhase.RUNNING -> "Seeding places $detail"
        SeedPhase.WAITING_NETWORK -> "Seeding paused at $detail: waiting for a network"
        SeedPhase.DONE -> "Seeding done"
        SeedPhase.FAILED -> if (failedCells > 0) "FAILED: $failedCells cell${if (failedCells == 1) "" else "s"} did not seed" + (counts?.takeIf { it.total > 0 }?.let { " (${it.detail()})" } ?: "") else "Seeding did not finish, seed again"
        SeedPhase.CANCELLED -> "Seeding cancelled at $detail"
    }
}

/** Persisted cell progress of one area (survives leaving the screen and process death). [total] 0 = nothing recorded yet. */
data class SeedProgress(val done: Int = 0, val total: Int = 0, val waiting: Boolean = false, val cancelled: Boolean = false, val queued: Boolean = false, val failed: Int = 0,
                        /** Cells ok so far (fresh, fetched or partial), failed so far, and the partial ones among the ok (see [SeedTally]); done = okCells + failCells. */
                        val okCells: Int = 0, val failCells: Int = 0, val partial: Int = 0) {
    fun detail(): String = SeedTally.describe(done, total, failCells, partial)
    val percent: Int get() = if (total <= 0) 0 else (done.toLong().coerceIn(0, total.toLong()) * 100 / total).toInt()
}

object SeedStates {
    /**
     * [work] = the unique work's state (null = no work at all), [areaStatus] = the pack's area row status (null = no row), [networkBlocked] = the admission refuses right now for a network reason.
     * An area can only read as active while its work is live, so it can never stay "seeding" forever.
     */
    fun derive(work: WorkInfo.State?, progress: SeedProgress, areaStatus: String?, networkBlocked: Boolean): SeedAreaState {
        val pct = progress.percent
        return when (work) {
            WorkInfo.State.ENQUEUED -> if (progress.waiting || networkBlocked) SeedAreaState(SeedPhase.WAITING_NETWORK, pct, counts = progress) else SeedAreaState(SeedPhase.QUEUED, pct, counts = progress)
            WorkInfo.State.BLOCKED -> SeedAreaState(SeedPhase.QUEUED, pct, counts = progress)
            WorkInfo.State.RUNNING -> if (progress.queued) SeedAreaState(SeedPhase.QUEUED, pct, counts = progress) else SeedAreaState(SeedPhase.RUNNING, pct, counts = progress) // queued = waiting for the seed before it (one area at a time)
            WorkInfo.State.SUCCEEDED -> when {
                areaStatus == null -> SeedAreaState(SeedPhase.CANCELLED, pct, counts = progress) // the area was removed while it ran
                areaStatus == AREA_DONE -> SeedAreaState(SeedPhase.DONE, 100)
                else -> SeedAreaState(SeedPhase.FAILED, pct, progress.failed, progress)
            }
            WorkInfo.State.FAILED -> SeedAreaState(SeedPhase.FAILED, pct, progress.failed, progress)
            WorkInfo.State.CANCELLED -> SeedAreaState(SeedPhase.CANCELLED, pct, counts = progress)
            null -> when {
                progress.cancelled -> SeedAreaState(SeedPhase.CANCELLED, pct, counts = progress)
                areaStatus == AREA_DONE -> SeedAreaState(SeedPhase.DONE, 100)
                else -> SeedAreaState(SeedPhase.FAILED, pct, progress.failed, progress) // a "seeding" row with no live work (reconcile turns it into partial)
            }
        }
    }

    /** Reconcile: the areas still marked seeding whose work is not live. [live] = ids of areas with live work. */
    fun stalled(areas: List<Pair<String, String>>, live: Set<String>): List<String> = areas.filter { (id, status) -> status == AREA_SEEDING && id !in live }.map { it.first }

    fun isLive(s: WorkInfo.State?) = s == WorkInfo.State.ENQUEUED || s == WorkInfo.State.RUNNING || s == WorkInfo.State.BLOCKED
}

/** The seed's network rule: any connected network by default; unmetered only while the user's global "Photos and big uploads: Wi-Fi only" (Data saver) is ON. */
object SeedNetwork {
    fun requirement(mode: DataMode): androidx.work.NetworkType = if (mode == DataMode.WIFI_ONLY) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED
}

/** A stable, position-free key for an area id (the id itself is a box and must not appear in work names, work data or notifications). */
object SeedKeys {
    fun of(areaId: String): String = MessageDigest.getInstance("SHA-256").digest(areaId.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
}

/** Cell progress per area key in app-private prefs: counts only. Monotonic within a seed so a resume never makes the % go back. */
class SeedProgressStore(app: Context) {
    private val p = app.getSharedPreferences("place_seed_progress", Context.MODE_PRIVATE)

    fun get(key: String) = SeedProgress(p.getInt("$key.done", 0), p.getInt("$key.total", 0), p.getBoolean("$key.wait", false), p.getBoolean("$key.cancel", false), p.getBoolean("$key.queued", false), p.getInt("$key.failed", 0),
        p.getInt("$key.ok", 0), p.getInt("$key.failc", 0), p.getInt("$key.partial", 0))

    /** A new request for the area: counts are kept (the seed resumes), the flags are reset. The failed count at this moment is remembered as the base the retry converts failed -> ok from. */
    fun begin(key: String) { p.edit().putBoolean("$key.wait", false).putBoolean("$key.cancel", false).putBoolean("$key.queued", false).putInt("$key.failed", 0).putInt("$key.failc0", p.getInt("$key.failc", 0)).apply() }

    /**
     * [failed] = cells failed so far in this run, [partial] = ok cells stored without OSM extras, [fetched] = cells fetched (not fresh) so far in this run. Processed (done) never goes back; the
     * ok and failed counts follow it. While a resumed or retried run is still re-walking cells the earlier run already counted (done below the stored value), every non-fresh cell it
     * fetches was a failed/partial one before, so failed = (failed at begin) - fetched and ok = stored done - failed: failed shrinks as cells are retried, ok grows by the same amount, processed stays.
     */
    fun progress(key: String, done: Int, total: Int, failed: Int = 0, partial: Int = 0, fetched: Int = 0) {
        val cur = get(key)
        val keep = cur.total == total && cur.done > done
        val d = if (keep) cur.done else done
        val f = if (keep) (p.getInt("$key.failc0", cur.failCells) - fetched).coerceIn(0, cur.failCells) else failed.coerceIn(0, d)
        val part = if (keep) cur.partial.coerceAtMost(d - f) else partial.coerceAtMost(d - f)
        p.edit().putInt("$key.done", d).putInt("$key.total", total).putInt("$key.failc", f).putInt("$key.ok", d - f).putInt("$key.partial", part).putBoolean("$key.wait", false).apply()
    }

    fun setWaiting(key: String, on: Boolean) { p.edit().putBoolean("$key.wait", on).apply() }
    fun setQueued(key: String, on: Boolean) { p.edit().putBoolean("$key.queued", on).apply() }
    /** Cells left failed (or without OSM extras) when the seed ended: a count only, never which cells or where. */
    fun setFailed(key: String, n: Int) { p.edit().putInt("$key.failed", n.coerceAtLeast(0)).apply() }
    fun markCancelled(key: String) { p.edit().putBoolean("$key.cancel", true).putBoolean("$key.wait", false).apply() }
    fun clear(key: String) { p.edit().remove("$key.done").remove("$key.total").remove("$key.wait").remove("$key.cancel").remove("$key.queued").remove("$key.failed").remove("$key.ok").remove("$key.failc").remove("$key.failc0").remove("$key.partial").apply() }
    fun clearAll() { p.edit().clear().apply() }
}

/**
 * How long a fetched cell takes on THIS phone: a running total of milliseconds and a count of fetched cells, nothing else (no cell, no place, no position). Skipped (fresh) cells and retry waits
 * are not counted. The weights fade past [KEEP] cells so a change of network shows up. Used only for the seed estimate once [MIN_CELLS] cells are in.
 */
class SeedTimingStore(app: Context) {
    private val p = app.getSharedPreferences("place_seed_timing", Context.MODE_PRIVATE)

    @Synchronized fun add(ms: Long) {
        var n = p.getLong("n", 0); var total = p.getLong("ms", 0)
        n++; total += ms.coerceIn(0, MAX_MS)
        if (n > KEEP) { total = total * KEEP / n; n = KEEP }
        p.edit().putLong("n", n).putLong("ms", total).apply()
    }

    /** Seconds per fetched cell from this phone's own seeds, or null while fewer than [MIN_CELLS] cells were timed. */
    fun secondsPerCell(): Double? = Timing.secondsPerCell(p.getLong("n", 0), p.getLong("ms", 0))

    object Timing {
        fun secondsPerCell(cells: Long, totalMs: Long): Double? = if (cells < MIN_CELLS || totalMs <= 0) null else totalMs / 1000.0 / cells
    }

    companion object {
        const val MIN_CELLS = 10L
        const val KEEP = 200L
        private const val MAX_MS = 10 * 60_000L // one cell is never longer than this in the average (a stuck cell must not skew it)
    }
}
