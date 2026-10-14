package com.rokid.context.work.budget

import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Rolling view of the budget: calls started in the last hour by class, and how many callers wait for a token. */
data class BudgetStats(val callsLastHour: Map<CallClass, Int> = emptyMap(), val waiters: Int = 0)

/**
 * Token bucket in front of every provider call (spec P2.1): one token per [periodMs] (10/12/15 s), at most [burst] banked.
 * Every 5th minted token is reserved for [CallClass.NUDGE]; a reservation unused for [RESERVATION_MS] becomes an ordinary
 * token. A nudge with no reserved token borrows the next ordinary one (ahead of extraction, so it delays it by one token).
 * CAPTION_QUEUE and BACKFILL take ordinary tokens only, after every NUDGE/EXTRACT waiter. USER calls never wait but spend
 * a token (the bucket may go into debt, which delays everyone else). Transport errors refund the token.
 */
class CallBudget(
    private val clock: () -> Long,
    private val periodMs: () -> Long,
    private val burst: Int = 2,
) : CallGate {
    private enum class Kind { NORMAL, RESERVED }
    private class Waiter(val priority: Int, val seq: Long)

    private val lock = Any()
    private var normal = burst            // may go negative: USER calls in debt
    private val reserved = ArrayList<Long>() // mint times of reserved tokens
    private var minted = 0L
    private var lastMint = clock()
    private var seq = 0L
    private val waiters = ArrayList<Waiter>()
    private val history = HashMap<CallClass, ArrayDeque<Long>>()
    private val version = MutableStateFlow(0)
    private val _stats = MutableStateFlow(BudgetStats())
    val stats: StateFlow<BudgetStats> = _stats.asStateFlow()

    override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
        val kind = acquire(callClass)
        try {
            return block()
        } catch (c: CancellationException) {
            if (c is TimeoutCancellationException) refund(kind)
            throw c
        } catch (e: IOException) {
            refund(kind)
            throw e
        }
    }

    private suspend fun acquire(callClass: CallClass): Kind {
        if (callClass == CallClass.USER) return synchronized(lock) {
            val now = refill()
            normal-- // spends a token, or goes into debt
            record(callClass, now)
            Kind.NORMAL
        }
        val w = synchronized(lock) { Waiter(priority(callClass), seq++).also { waiters.add(it); publish(clock()) } }
        try {
            while (true) {
                var wait = 0L
                var seen = 0
                val got = synchronized(lock) {
                    val now = refill()
                    seen = version.value
                    val k = if (waiters.none { it.priority < w.priority || (it.priority == w.priority && it.seq < w.seq) }) take(callClass) else null
                    if (k != null) { waiters.remove(w); record(callClass, now) } else wait = untilNextEvent(now)
                    k
                }
                if (got != null) { version.value++; return got }
                withTimeoutOrNull(wait) { version.first { it != seen } }
            }
        } catch (t: Throwable) {
            synchronized(lock) { if (waiters.remove(w)) publish(clock()) }
            version.value++
            throw t
        }
    }

    /** NUDGE (0) borrows ahead of EXTRACT/OTHER (1), which go ahead of CAPTION_QUEUE/BACKFILL (2). */
    private fun priority(c: CallClass) = when (c) {
        CallClass.NUDGE -> 0
        CallClass.EXTRACT, CallClass.OTHER, CallClass.USER -> 1
        CallClass.CAPTION_QUEUE, CallClass.BACKFILL -> 2
    }

    private fun take(c: CallClass): Kind? {
        if (c == CallClass.NUDGE && reserved.isNotEmpty()) { reserved.removeAt(0); return Kind.RESERVED }
        if (normal > 0) { normal--; return Kind.NORMAL }
        return null
    }

    private fun refund(kind: Kind) {
        synchronized(lock) {
            val now = clock()
            if (kind == Kind.RESERVED) reserved.add(now) else normal++
            publish(now)
        }
        version.value++
    }

    /** Mints due tokens and releases stale reservations. Call under [lock]; returns now. */
    private fun refill(): Long {
        val now = clock()
        val period = periodMs().coerceAtLeast(1L)
        val iter = reserved.iterator()
        while (iter.hasNext()) if (now - iter.next() >= RESERVATION_MS) { iter.remove(); normal++ }
        if (now < lastMint) lastMint = now
        while (now - lastMint >= period) {
            lastMint += period
            if (normal + reserved.size >= burst) { lastMint = now; break } // bucket full: no banking beyond burst
            minted++
            if (minted % RESERVE_EVERY == 0L && reserved.isEmpty()) reserved.add(lastMint) else normal++
        }
        if (normal + reserved.size >= burst) lastMint = now
        return now
    }

    private fun untilNextEvent(now: Long): Long {
        val next = lastMint + periodMs().coerceAtLeast(1L) - now
        val expiry = reserved.minOfOrNull { it + RESERVATION_MS - now } ?: Long.MAX_VALUE
        return minOf(next, expiry).coerceAtLeast(1L)
    }

    private fun record(c: CallClass, now: Long) {
        history.getOrPut(c) { ArrayDeque() }.addLast(now)
        publish(now)
    }

    private fun publish(now: Long) {
        val counts = HashMap<CallClass, Int>()
        for ((c, q) in history) {
            while (q.isNotEmpty() && now - q.first() > HOUR_MS) q.removeFirst()
            if (q.isNotEmpty()) counts[c] = q.size
        }
        _stats.value = BudgetStats(counts, waiters.size)
    }

    private companion object {
        const val RESERVE_EVERY = 5
        const val RESERVATION_MS = 60_000L
        const val HOUR_MS = 3_600_000L
    }
}
