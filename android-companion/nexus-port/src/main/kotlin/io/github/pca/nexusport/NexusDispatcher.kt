package io.github.pca.nexusport

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The ONE place binder calls leave the sink from (Codex v2 r2 P1-1): privacy shutdown must never depend on a remote call returning. The sink admits under its lock and the fences' locks (cheap, atomic) and only ENQUEUES here;
 * a single worker thread takes the items in order and makes each transport call OUTSIDE every lock, on a pool thread, waiting at most [callTimeoutMs] (a hung bridge costs one pool thread, never a lock). A timeout or an
 * exception marks the bridge unhealthy: the sink trips, drops what is pending and owes a wipe; until the backoff ([backoffBaseMs] doubling up to [backoffMaxMs]) is over no further call is made, then one call probes.
 * [inline] (tests only) runs every item at once on the caller's thread and every call without a timeout. Never holds its own lock while running an item or a discard callback.
 */
internal class NexusDispatcher private constructor(
    private val inline: Boolean,
    val callTimeoutMs: Long,
    private val maxQueue: Int,
    private val backoffBaseMs: Long,
    private val backoffMaxMs: Long,
) {
    /** [guarded]: a show that a trip discards unsent. [run] executes it; [discard] completes it without running (given why). */
    class Item(val guarded: Boolean, val run: () -> Unit, val discard: (DropReason) -> Unit = {})

    private val qLock = Object()
    private val queue = ArrayDeque<Item>()
    private var worker: Thread? = null
    private var closed = false
    private val pool: ThreadPoolExecutor? = if (inline) null else ThreadPoolExecutor(0, MAX_STUCK, 3, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(r, "pca-nexus-call").also { it.isDaemon = true }
    }

    @Volatile private var down = false
    @Volatile private var retryAtNanos = 0L
    private var failures = 0

    /** Queues [item] (when [urgent] ahead of every guarded item, in order among the unguarded ones); false = the queue is full of unguarded items (a guarded one is evicted first). Never runs [item] under a lock of its own, never blocks. */
    fun submit(item: Item, urgent: Boolean = false): Boolean {
        if (inline) { runItem(item); return true }
        val evicted = ArrayList<Item>()
        var ok = true
        synchronized(qLock) {
            if (closed) { ok = false; return@synchronized }
            while (queue.size >= maxQueue) {
                val g = queue.indexOfFirst { it.guarded }
                if (g < 0) { ok = false; break }
                evicted += queue.removeAt(g)
            }
            if (ok) {
                if (urgent) { val i = queue.indexOfFirst { it.guarded }; if (i < 0) queue.addLast(item) else queue.add(i, item) } else queue.addLast(item)
                if (worker == null) worker = Thread(::loop, "pca-nexus-dispatch").also { it.isDaemon = true; it.start() }
                qLock.notifyAll()
            }
        }
        evicted.forEach { runDiscard(it, DropReason.TRANSPORT) }
        return ok
    }

    /** Removes every pending guarded item and returns it for the caller to discard (the caller completes them; nothing here runs a callback under qLock). */
    fun takeGuarded(): List<Item> {
        if (inline) return emptyList()
        synchronized(qLock) {
            val out = queue.filter { it.guarded }
            queue.removeAll { it.guarded }
            return out
        }
    }

    fun pending(): Int = synchronized(qLock) { queue.size }

    private fun loop() {
        while (true) {
            val item = synchronized(qLock) {
                while (queue.isEmpty() && !closed) qLock.wait()
                if (closed) return
                queue.removeFirst()
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

    /** Best effort on its own pool thread, never queued behind the worker (the trip epoch notice must overtake a slow call). Silently dropped when every pool thread is stuck. */
    fun async(block: () -> Unit) {
        if (pool == null) { try { block() } catch (_: Throwable) { }; return }
        try { pool.execute { try { block() } catch (_: Throwable) { } } } catch (_: RejectedExecutionException) { }
    }

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
        synchronized(qLock) { closed = true; rest = queue.toList(); queue.clear(); qLock.notifyAll() }
        pool?.shutdown()
        return rest
    }

    companion object {
        private const val MAX_STUCK = 4
        fun direct() = NexusDispatcher(true, 0, Int.MAX_VALUE, 0, 0)
        fun threaded(callTimeoutMs: Long = 2_000, maxQueue: Int = 32, backoffBaseMs: Long = 1_000, backoffMaxMs: Long = 30_000) =
            NexusDispatcher(false, callTimeoutMs, maxQueue, backoffBaseMs, backoffMaxMs)
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
