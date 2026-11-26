package com.rokid.context.memory.place

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The try number of the cell being fetched (1 = first pass, 2..5 = retry rounds), carried in the coroutine context so the HTTP layer can log it. A number only. */
class SeedAttempt(val n: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SeedAttempt>
}

/**
 * The ONE limiter and the ONE set of counters shared by every place request in the process (Wikipedia, Wikidata, Overpass, every cell): after a 429 or 503 no request of the SAME server group
 * (Wikimedia = Wikipedia + Wikidata, or Overpass) starts until the pause is over, whichever cell asks; the other group is not stopped. The pause is the numeric Retry-After (seconds, at most [RETRY_AFTER_MAX_S]) when the server sent one, else
 * exponential 10, 20, 40, 80 s capped at 120 s while the answers keep being 429/503; a successful answer resets the exponent.
 *
 * Diagnostics are CONTENT-FREE: tokens and numbers only (service token, status, attempt, Retry-After seconds, wait ms, a failure class token). Never a URL, a query, a coordinate, a cell or a place.
 */
class PlaceNetGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val log: (String) -> Unit = {},
    /** A fixed-format " net=wifi saver=0 dsaver=0 state=RUNNING" tail, supplied by the phone (no SSID, no names). */
    private val env: () -> String = { "" },
) {
    private val lock = Any()
    /** One pause and one streak per server group (see [groupOf]): an Overpass 503 never stops Wikipedia/Wikidata, and the reverse. */
    private val pausedUntilBy = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val streaks = HashMap<String, Int>()
    private fun pausedUntilOf(group: String?): Long = if (group == null) (pausedUntilBy.values.maxOrNull() ?: 0L) else (pausedUntilBy[group] ?: 0L)
    private var startMs = clock()
    private var lastSummaryMs = startMs
    private var reqs = 0; private var c2xx = 0; private var c4xx = 0; private var c429 = 0; private var c5xx = 0; private var cNet = 0; private var pauses = 0
    private val durations = ArrayDeque<Long>()

    /** Waits (in slices of at most one second, asking the seed admission and the zone fence each time) until the pause of [service]'s group is over (null: until every group's pause is over). */
    suspend fun await(service: String? = null) {
        val group = service?.let { groupOf(it) }
        while (true) {
            val wait = pausedUntilOf(group) - clock()
            if (wait <= 0) return
            sleep(wait.coerceAtMost(1_000L))
            seedAdmitCheck(); zoneCheck()
        }
    }

    fun ok(service: String, ms: Long) = synchronized(lock) { reqs++; c2xx++; record(ms); streaks[groupOf(service)] = 0 }

    /** A request failed. [status] is the HTTP code or null for a network error ([cls] is then timeout, dns, tls or io). */
    fun failed(service: String, status: Int?, cls: String, retryAfterS: Int?, ms: Long, attempt: Int) {
        var wait = 0L
        val group = groupOf(service)
        var streak = 0
        synchronized(lock) {
            reqs++; record(ms)
            when {
                status == null -> cNet++
                status == 429 -> c429++
                status in 400..499 -> c4xx++
                status in 500..599 -> c5xx++
                else -> c4xx++
            }
            if (status == 429 || status == 503) {
                val now = clock()
                val until = pausedUntilBy[group] ?: 0L
                if (now >= until) streaks[group] = (streaks[group] ?: 0) + 1 // a new episode (a request that ran after the last pause ended failed again); requests already in flight do not compound it
                streak = streaks[group] ?: 1
                wait = if (retryAfterS != null && retryAfterS >= 0) retryAfterS.coerceAtMost(RETRY_AFTER_MAX_S) * 1000L else backoffMs(streak)
                pausedUntilBy[group] = maxOf(until, now + wait); pauses++
            }
        }
        val st = status ?: 0
        val ra = retryAfterS ?: -1
        log("place req failed svc=$service status=$st cls=$cls attempt=$attempt retryAfterS=$ra waitMs=$wait ms=$ms")
        if (wait > 0) log("place pause group=$group status=$st waitMs=$wait streak=$streak svc=$service")
    }

    private fun record(ms: Long) { durations.addLast(ms); if (durations.size > 200) durations.removeFirst() }

    /** Starts a fresh set of counters for one seed (the pauses themselves are kept: they belong to the servers, not to the seed). */
    fun reset() = synchronized(lock) { startMs = clock(); lastSummaryMs = startMs; reqs = 0; c2xx = 0; c4xx = 0; c429 = 0; c5xx = 0; cNet = 0; pauses = 0; durations.clear() }

    /** The periodic summary: every [SUMMARY_CELLS] cells or [SUMMARY_MS] ms, whichever comes first. */
    fun tick(done: Int, failedCells: Int, total: Int, partial: Int = 0, gate: Int = done) {
        val now = clock()
        if (gate % SUMMARY_CELLS != 0 && now - lastSummaryMs < SUMMARY_MS) return
        lastSummaryMs = now
        line("seed progress", done, failedCells, total, partial)
    }

    /** The final line of one seed. */
    fun finish(cells: Int, failedCells: Int, retries: Int, partial: Int = 0) {
        val ms = (clock() - startMs).coerceAtLeast(0)
        val s = snapshot()
        log("seed final cells=$cells ok=${cells - failedCells} failed=$failedCells partial=$partial okPct=${SeedTally.okPct(cells - failedCells, failedCells)} failedPct=${SeedTally.failedPct(cells - failedCells, failedCells)} retries=$retries durationMs=$ms reqs=${s.reqs} s2xx=${s.c2xx} s4xx=${s.c4xx} s429=${s.c429} s5xx=${s.c5xx} snet=${s.cNet} pauses=${s.pauses}")
    }

    private fun line(what: String, done: Int, failedCells: Int, total: Int, partial: Int) {
        val ok = (done - failedCells).coerceAtLeast(0)
        val s = snapshot()
        val rpm = (s.reqs * 60_000L / s.elapsed.coerceAtLeast(1_000L)).toInt()
        val backoff = (pausedUntilOf(null) - clock()).coerceAtLeast(0)
        val med = s.median
        val tail = try { env() } catch (e: Exception) { "" }
        log("$what cells=$done failed=$failedCells total=$total ok=$ok partial=$partial waiting=${(total - done).coerceAtLeast(0)} donePct=${SeedTally.donePct(done, total)} okPct=${SeedTally.okPct(ok, failedCells)} failedPct=${SeedTally.failedPct(ok, failedCells)} s2xx=${s.c2xx} s4xx=${s.c4xx} s429=${s.c429} s5xx=${s.c5xx} snet=${s.cNet} rpm=$rpm backoffMs=$backoff medianMs=$med$tail")
    }

    private class Snap(val reqs: Int, val c2xx: Int, val c4xx: Int, val c429: Int, val c5xx: Int, val cNet: Int, val pauses: Int, val median: Long, val elapsed: Long)
    private fun snapshot(): Snap = synchronized(lock) {
        val sorted = durations.sorted()
        Snap(reqs, c2xx, c4xx, c429, c5xx, cNet, pauses, if (sorted.isEmpty()) 0L else sorted[sorted.size / 2], clock() - startMs)
    }

    companion object {
        const val GROUP_OVERPASS = "overpass"
        const val GROUP_WIKIMEDIA = "wikimedia"
        /** The server group of a service token: Overpass on its own, Wikipedia (geosearch, extracts) and Wikidata together as Wikimedia. */
        fun groupOf(service: String): String = if (service == "overpass") GROUP_OVERPASS else GROUP_WIKIMEDIA
        const val SUMMARY_CELLS = 25
        const val SUMMARY_MS = 60_000L
        const val RETRY_AFTER_MAX_S = 300
        const val BACKOFF_BASE_MS = 10_000L
        const val BACKOFF_MAX_MS = 120_000L
        /** 10, 20, 40, 80 s, then 120 s for every further consecutive 429/503. */
        fun backoffMs(streak: Int): Long = (BACKOFF_BASE_MS shl (streak - 1).coerceIn(0, 10)).coerceAtMost(BACKOFF_MAX_MS)
        /** The worker's state token for the summary line ("RUNNING" / "WAITING_NETWORK"); set by the phone's seed worker. */
        @Volatile var workerState: String = "NONE"
    }
}
