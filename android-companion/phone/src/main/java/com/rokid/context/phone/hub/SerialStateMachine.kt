package com.rokid.context.phone.hub

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What one event did: the next [state], the [effects] to run, an optional [recheck] event and the [reply] for the submitter.
 * [recheck] is the re-validation step: it is queued at the TAIL, so every event submitted while this step ran (its snapshot write included) is reduced first,
 * and the submitter's reply is the recheck's (a destructive effect is decided there, from the latest state, never from the state that started the step).
 */
data class Transition<S, E, F>(val state: S, val effects: List<F> = emptyList(), val recheck: E? = null, val reply: Any? = null)

/**
 * The one serialised state machine ("actor") of the hub's background state (AutoStop handoff 2026-11-07, Codex reviews 4 to 7): every input is an EVENT, reduced one
 * at a time by a PURE reducer `(state, event, now) -> (state', effects)`; nothing else mutates the state.
 *
 * Write-behind persistence (Codex review 7). The IN-MEMORY state is authoritative: every event is applied to it at once, whatever any save did, and every effect is
 * driven by it. Persistence only mirrors it: after each step whose state differs from the last saved snapshot, ONE coalescing "persist latest" writes the WHOLE
 * latest snapshot ([save], on the serial context, before that step's effects run, so normally the stop / start a step decides is already durable). A failed write
 * (false or a throw) never re-applies or re-orders anything: the machine reduces [persistFailed] (in-memory only: the reducer turns conservative, e.g. it never stops
 * the hub while its latest snapshot is unsaved) and retries the write of whatever is latest then, on [RETRY_MS] (capped), until it succeeds; then [persistRecovered]
 * is reduced. A process that dies meanwhile restarts from the last saved snapshot ([load]).
 *
 * Steps run on [executor] (Dispatchers.IO in production, so no write ever runs on the main thread; a direct executor in tests). [submit] never blocks: it queues
 * the event and kicks a drain; one drainer at a time (an atomic flag), so the executor may be multi-threaded. [load] runs on the executor too.
 */
class SerialStateMachine<S : Any, E : Any, F : Any>(
    private val load: () -> S,
    private val reduce: (S, E, Long) -> Transition<S, E, F>,
    private val persisted: (S) -> Any?,
    /** Writes the whole snapshot; true = durable. */
    private val save: (S) -> Boolean,
    private val now: () -> Long,
    private val executor: Executor,
    private val prepare: (S, E) -> E = { _, e -> e },
    private val run: (F) -> E? = { null },
    /** Test hook: every reduced event, the time it saw and the state after it (before its effects; after its snapshot write was attempted). */
    private val onStep: (E, Long, S) -> Unit = { _, _, _ -> },
    private val onError: (Throwable) -> Unit = {},
    /** The in-memory event a failed write becomes ([attempt]: consecutive failures); it must not change the persisted part. Null = nothing to reduce. */
    private val persistFailed: ((Int) -> E)? = null,
    /** Reduced once the latest snapshot is saved again after a failure (or the state went back to the saved one). */
    private val persistRecovered: E? = null,
    /** Runs the delayed write retry (null = one shared daemon timer thread; tests pass their own). */
    schedule: ((Long, Runnable) -> Unit)? = null,
) {
    private class Job<E>(val event: E?, val done: CompletableDeferred<Any?>)

    private val queue = ConcurrentLinkedQueue<Job<E>>()
    private val draining = AtomicBoolean(false)
    @Volatile private var current: S? = null
    // Touched only by the drainer:
    /** The persisted part of the last snapshot written. */
    private var saved: Any? = null
    private var failures = 0
    private var retryArmed = false
    /** Bookkeeping steps reduced inside a write, reported to [onStep] after the step that caused them (so the trace stays in reduction order). */
    private val bookkeeping = ArrayList<Triple<E, Long, S>>()
    private val schedule: (Long, Runnable) -> Unit = schedule ?: { ms, r -> TIMER.schedule(r, ms, java.util.concurrent.TimeUnit.MILLISECONDS) }

    /** The authoritative in-memory state; null before it was loaded. */
    fun state(): S? = current

    /** Queues [e]; the deferred completes with the reply of its chain (its feedback events and its recheck included). Never blocks, any thread. */
    fun submit(e: E): CompletableDeferred<Any?> {
        val j = Job<E>(e, CompletableDeferred()); queue.add(j); kick(); return j.done
    }

    /** Loads the state on the executor now (instead of at the first event), so readers of [state] get it soon. */
    fun warm() = kick()

    private fun kick() { try { executor.execute(::drain) } catch (t: Throwable) { onError(t) } }

    private fun drain() {
        while (draining.compareAndSet(false, true)) {
            try {
                if (current == null) try { loadNow() } catch (t: Throwable) { onError(t) }
                while (true) process(queue.poll() ?: break)
            } finally { draining.set(false) }
            if (queue.isEmpty()) return // else a submit raced the release: drain again
        }
    }

    private fun process(j: Job<E>) {
        try {
            if (current == null) loadNow()
            val e = j.event
            if (e == null) { retryArmed = false; persistLatest(); flushBookkeeping(); j.done.complete(null); return } // the retry timer: write whatever is latest now
            val (reply, recheck) = step(e)
            if (recheck != null) queue.add(Job(recheck, j.done)) else j.done.complete(reply)
        } catch (t: Throwable) { onError(t); j.done.completeExceptionally(t) }
    }

    private fun loadNow() { val s = load(); saved = persisted(s); current = s }

    private fun step(first: E): Pair<Any?, E?> {
        var reply: Any? = null; var recheck: E? = null
        val pending = ArrayDeque<E>().apply { add(first) }
        while (pending.isNotEmpty()) {
            val e = prepare(current!!, pending.removeFirst())
            val t = now()
            val tr = reduce(current!!, e, t)
            current = tr.state // applied at once: the in-memory state is authoritative
            persistLatest()
            onStep(e, t, tr.state); flushBookkeeping()
            if (tr.reply != null) reply = tr.reply
            if (tr.recheck != null) recheck = tr.recheck
            for (f in tr.effects) {
                val feedback = try { run(f) } catch (x: Exception) { onError(x); null }
                if (feedback != null) pending.add(feedback)
            }
        }
        return reply to recheck
    }

    /** The coalescing write-behind: writes the LATEST snapshot when it differs from the saved one; on failure arms one retry (capped back-off). */
    private fun persistLatest() {
        val s = current ?: return
        val p = persisted(s)
        if (p == saved) { if (failures > 0) { failures = 0; internal(persistRecovered) }; return }
        val ok = try { save(s) } catch (x: Exception) { onError(x); false }
        if (ok) {
            saved = p
            if (failures > 0) { failures = 0; internal(persistRecovered) }
            return
        }
        failures++
        persistFailed?.let { internal(it(failures)) }
        if (!retryArmed) {
            retryArmed = true
            schedule(RETRY_MS[(failures - 1).coerceIn(0, RETRY_MS.lastIndex)], Runnable { queue.add(Job(null, CompletableDeferred())); kick() })
        }
    }

    /** Reduces an in-memory bookkeeping event (persistence failed / recovered) and runs its effects; it may not change the persisted part. */
    private fun internal(e: E?) {
        if (e == null) return
        val before = current!!
        val t = now()
        val tr = reduce(before, e, t)
        check(persisted(tr.state) == persisted(before)) { "a persistence bookkeeping event must not change the persisted state" }
        current = tr.state
        bookkeeping += Triple(e, t, tr.state)
        for (f in tr.effects) try { run(f) } catch (x: Exception) { onError(x) }
    }

    private fun flushBookkeeping() { if (bookkeeping.isEmpty()) return; val b = bookkeeping.toList(); bookkeeping.clear(); b.forEach { (e, t, s) -> onStep(e, t, s) } }

    companion object {
        /** Runs the step on the submitting thread (tests; a machine without disk IO). */
        val DIRECT: Executor = Executor { it.run() }
        /** Write retry delays after consecutive failures; the last one repeats until a write succeeds. */
        val RETRY_MS: List<Long> = listOf(250L, 1_000L, 4_000L, 15_000L, 60_000L)
        private val TIMER: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "serial-machine-retry").apply { isDaemon = true } }
        }
    }
}
