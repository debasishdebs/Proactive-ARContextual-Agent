package io.github.pca.nexusport

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * The ONE place binder calls leave the sink from (Codex v2 r2 P1-1, r3 P1-1/P1-4): privacy shutdown must never depend on a remote call returning. The sink admits under its lock and the fences' locks (cheap, atomic) and only
 * ENQUEUES here; a single worker thread takes the items in order and makes each transport call OUTSIDE every lock, on a pool thread, waiting at most [callTimeoutMs] (a hung bridge costs one pool thread, never a lock). A
 * timeout or an exception marks the bridge unhealthy: the sink trips, drops what is pending and owes a wipe; until the backoff ([backoffBaseMs] doubling up to [backoffMaxMs]) is over no further call is made, then one call probes.
 *
 * Two lanes (r3 P1-4): CONTENT (guarded shows, capacity [maxQueue], a full lane REJECTS the new show) and PRIVACY (hides, stops, wipes, features, capacity [maxPrivacy], never touched by content and served first). A privacy
 * submit that is still rejected is the caller's to run through [runFresh].
 *
 * The delivery lease (r3 P1-1): a content send holds the SHARED lease for the duration of its (timeout-bounded) binder call; a trip takes it EXCLUSIVE with a bounded wait ([exclusiveWaitMs]) so no send is in flight while the
 * bridge learns the new epoch, and proceeds anyway when the wait times out (a hung send never blocks a trip). [tripCall] delivers the epoch on its own threads, never behind the content queue.
 * [inline] (tests only) runs every item at once on the caller's thread, every call without a timeout and has no lease. Never holds its own lock while running an item or a discard callback.
 */
internal class NexusDispatcher private constructor(
    private val inline: Boolean,
    val callTimeoutMs: Long,
    private val maxQueue: Int,
    private val backoffBaseMs: Long,
    private val backoffMaxMs: Long,
    private val maxPrivacy: Int,
    val exclusiveWaitMs: Long,
    val tripTimeoutMs: Long,
    val sharedWaitMs: Long,
) {
    /** [guarded] (content lane): a show that a trip discards unsent. [run] executes it; [discard] completes it without running (given why). [tag] identifies what a show was for (its handle), so a withdrawal can cancel it. */
    class Item(val guarded: Boolean, val run: () -> Unit, val discard: (DropReason) -> Unit = {}, val tag: Any? = null)

    private val qLock = Object()
    private val content = ArrayDeque<Item>()
    private val privacy = ArrayDeque<Item>()
    private var worker: Thread? = null
    private var closed = false
    private val pool: ThreadPoolExecutor? = if (inline) null else ThreadPoolExecutor(0, MAX_STUCK, 3, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "pca-nexus-call").also { it.isDaemon = true }
    }
    /** The trip epoch notice's own threads: never shared with content or privacy calls. */
    private val tripPool: ThreadPoolExecutor? = if (inline) null else ThreadPoolExecutor(0, 2, 3, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "pca-nexus-trip").also { it.isDaemon = true; it.priority = Thread.MAX_PRIORITY }
    }
    private val lease = ReentrantReadWriteLock(true)
    private val fresh = AtomicInteger(0)

    @Volatile private var down = false
    @Volatile private var retryAtNanos = 0L
    private var failures = 0

    /** Queues [item] on its lane. False = that lane is full (or the dispatcher is closed). Never runs [item] under a lock of its own, never blocks. */
    fun submit(item: Item): Boolean {
        if (inline) { runItem(item); return true }
        var ok = true
        synchronized(qLock) {
            val q = if (item.guarded) content else privacy
            if (closed || q.size >= (if (item.guarded) maxQueue else maxPrivacy)) { ok = false; return@synchronized }
            q.addLast(item)
            if (worker == null) worker = Thread(::loop, "pca-nexus-dispatch").also { it.isDaemon = true; it.start() }
            qLock.notifyAll()
        }
        return ok
    }

    /** Removes every pending guarded item and returns it for the caller to discard (the caller completes them; nothing here runs a callback under qLock). */
    fun takeGuarded(): List<Item> {
        if (inline) return emptyList()
        synchronized(qLock) {
            val out = content.toList()
            content.clear()
            return out
        }
    }

    /** Removes the pending guarded items made for [tag] (a withdrawn handle) and returns them for the caller to discard. */
    fun takeTagged(tag: Any): List<Item> {
        if (inline) return emptyList()
        synchronized(qLock) {
            val out = content.filter { it.tag === tag }
            content.removeAll { it.tag === tag }
            return out
        }
    }

    fun pending(): Int = synchronized(qLock) { content.size + privacy.size }
    fun pendingContent(): Int = synchronized(qLock) { content.size }

    private fun loop() {
        while (true) {
            val item = synchronized(qLock) {
                while (content.isEmpty() && privacy.isEmpty() && !closed) qLock.wait()
                if (closed) return
                if (privacy.isNotEmpty()) privacy.removeFirst() else content.removeFirst()
            }
            runItem(item)
        }
    }

    private fun runItem(item: Item) { try { item.run() } catch (_: Throwable) { } }
    private fun runDiscard(item: Item, why: DropReason) { try { item.discard(why) } catch (_: Throwable) { } }

    /** One transport call on a pool thread, outside every lock, with the hard timeout. Null = it timed out or threw (the caller declares the bridge unhealthy). */
    fun call(block: () -> Int): Int? {
        if (pool == null) return try { block() } catch (_: Throwable) { null }
        val f = try { pool.submit(Callable { block() }) } catch (_: RejectedExecutionException) { return null }
        return try {
            f.get(callTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true); null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); null
        }
    }

    /** Best effort on its own pool thread, never queued behind the worker. Silently dropped when every pool thread is stuck. */
    fun async(block: () -> Unit) {
        if (pool == null) { try { block() } catch (_: Throwable) { }; return }
        try { pool.execute { try { block() } catch (_: Throwable) { } } } catch (_: RejectedExecutionException) { }
    }

    /** The privacy fallback when a lane rejected an operation that must not be lost: [block] runs on a fresh daemon thread (at most [MAX_FRESH] at once; beyond that the next tick retries). */
    fun runFresh(block: () -> Unit) {
        if (inline) { try { block() } catch (_: Throwable) { }; return }
        if (fresh.incrementAndGet() > MAX_FRESH) { fresh.decrementAndGet(); return }
        try {
            Thread({ try { block() } catch (_: Throwable) { } finally { fresh.decrementAndGet() } }, "pca-nexus-fresh").also { it.isDaemon = true }.start()
        } catch (_: Throwable) { fresh.decrementAndGet() }
    }

    /** The trip epoch notice: its own threads, at most [tripTimeoutMs]. Returns the transport code, or null when it timed out, threw or no thread was free. */
    fun tripCall(block: () -> Int): Int? {
        if (tripPool == null) return try { block() } catch (_: Throwable) { null }
        val f = try { tripPool.submit(Callable { block() }) } catch (_: RejectedExecutionException) { return null }
        return try {
            f.get(tripTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true); null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); null
        }
    }

    // ---- the delivery lease ----

    /** A content send is about to make its binder call. False = a trip is closing the lease (or the wait ran out): the send is dropped. */
    fun acquireShared(): Boolean {
        if (inline) return true
        return try { lease.readLock().tryLock(sharedWaitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    fun releaseShared() { if (!inline) try { lease.readLock().unlock() } catch (_: IllegalMonitorStateException) { } }

    /** A trip: waits at most [exclusiveWaitMs] for the sends in flight. True = held (no send is in flight); false = the wait ran out and the trip goes on without it. */
    fun tryExclusive(): Boolean {
        if (inline) return false
        return try { lease.writeLock().tryLock(exclusiveWaitMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    fun releaseExclusive() { if (!inline) try { lease.writeLock().unlock() } catch (_: IllegalMonitorStateException) { } }

    /** True when calls may be made: the bridge is healthy, or the backoff is over (the next call is the probe). */
    fun healthy(): Boolean = !down || System.nanoTime() - retryAtNanos >= 0

    fun markFailure() {
        synchronized(qLock) {
            failures = (failures + 1).coerceAtMost(30)
            val wait = (backoffBaseMs shl (failures - 1).coerceAtMost(20)).coerceIn(backoffBaseMs, backoffMaxMs)
            retryAtNanos = System.nanoTime() + wait * 1_000_000L
            down = true
        }
    }

    /** A call came back: true when the bridge was down until now. */
    fun markOk(): Boolean {
        if (!down) return false
        synchronized(qLock) { val was = down; down = false; failures = 0; return was }
    }

    /** Stops the worker and returns what was still queued (the caller discards it). Stuck pool threads are daemons and end with their call. */
    fun close(): List<Item> {
        if (inline) return emptyList()
        val rest: List<Item>
        synchronized(qLock) { closed = true; rest = content.toList() + privacy.toList(); content.clear(); privacy.clear(); qLock.notifyAll() }
        pool?.shutdown(); tripPool?.shutdown()
        return rest
    }

    companion object {
        private const val MAX_STUCK = 4
        private const val MAX_FRESH = 4
        /** The bound a trip waits for sends in flight, and the bound of the epoch notice: a trip returns within the sum (750 ms) whatever the bridge does. */
        const val EXCLUSIVE_WAIT_MS = 250L
        const val TRIP_CALL_MS = 500L
        const val SHARED_WAIT_MS = 1_000L
        fun direct() = NexusDispatcher(true, 0, Int.MAX_VALUE, 0, 0, Int.MAX_VALUE, 0, 0, 0)
        fun threaded(
            callTimeoutMs: Long = 2_000, maxQueue: Int = 32, backoffBaseMs: Long = 1_000, backoffMaxMs: Long = 30_000,
            maxPrivacy: Int = 64, exclusiveWaitMs: Long = EXCLUSIVE_WAIT_MS, tripTimeoutMs: Long = TRIP_CALL_MS, sharedWaitMs: Long = SHARED_WAIT_MS,
        ) = NexusDispatcher(false, callTimeoutMs, maxQueue, backoffBaseMs, backoffMaxMs, maxPrivacy, exclusiveWaitMs, tripTimeoutMs, sharedWaitMs)
    }
}

/**
 * The history wipe PCA owes the Nexus hub, as a monotonic GENERATION (Codex v2 r2 P1-3). Every new debt (Forget, Delete all, mode off, an unhealthy bridge) bumps it and is persisted before anything else happens; a wipe
 * request carries the generation it covers and the hub's acknowledgement settles the debt only up to that generation, so a late acknowledgement of an older wipe can never clear a newer debt.
 */
interface WipeLedger {
    fun owed(): Boolean
    fun generation(): Long
    /** Records a NEW debt (persisted before returning) and returns its generation. */
    fun owe(): Long
    /** The hub acknowledged generation [upTo]: the debt is paid only when no newer generation was incurred since. */
    fun settle(upTo: Long)
    /** A notice may be in the hub's history from now on (persisted once per generation, BEFORE the notice is sent). */
    fun noteShown() = Unit
}

/** In memory (tests, and the sink's default). */
open class MemoryWipeLedger(owed: Boolean = false) : WipeLedger {
    private var gen = if (owed) 1L else 0L
    private var debt = owed
    @Synchronized override fun owed() = debt
    @Synchronized override fun generation() = gen
    @Synchronized override fun owe(): Long { gen++; debt = true; return gen }
    @Synchronized override fun settle(upTo: Long) { if (upTo >= gen) debt = false }
}
