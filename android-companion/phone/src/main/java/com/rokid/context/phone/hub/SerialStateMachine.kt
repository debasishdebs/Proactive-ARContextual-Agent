package com.rokid.context.phone.hub

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What one event did: the next [state], the [effects] to run once that state is durable, an optional [recheck] event and the [reply] for the submitter.
 * [recheck] is the re-validation step: it is queued at the TAIL, so every event submitted while this step was committing is reduced first, and the submitter's
 * reply is the recheck's (a destructive effect is decided there, from the latest state, never from the state that started the commit).
 */
data class Transition<S, E, F>(
    val state: S, val effects: List<F> = emptyList(), val recheck: E? = null, val reply: Any? = null,
    /** A delayed retry (persistence failure): [retry] is submitted again after [retryAfterMs], as its own job. */
    val retry: E? = null, val retryAfterMs: Long = 0L,
)

/**
 * The one serialised state machine ("actor") of the hub's background state (AutoStop handoff 2026-11-07, Codex review 4): every input is an EVENT, reduced one at a
 * time by a PURE reducer `(state, event, now) -> (state', effects)`; nothing else mutates the state. One step:
 *  1. [prepare] (on the serial context) may fill an event with freshly sampled inputs (cheap in-memory reads);
 *  2. [reduce] computes the next state;
 *  3. when its persisted part ([persisted]) changed, [save] commits the WHOLE snapshot (one record) before anything else: the in-memory state is published
 *     only after the commit, so the persisted and the in-memory snapshot never diverge, and a later save always writes the later snapshot;
 *  4. [run] executes the effects; an effect may return a feedback event, reduced next, before any queued event (a start's outcome, for example);
 *  5. a [Transition.recheck] goes to the tail of the queue (see [Transition]).
 * A failed save (false or a throw, Codex review 5) is NOT treated as durable: the new state is not published, its effects never run, and the failure is
 * itself an event ([persistFailed], reduced against the unchanged state, which must leave the persisted part alone) whose reducer decides the safe reply,
 * the log and a bounded delayed retry ([Transition.retry]). The first successful save after a failure is followed by [persistRecovered].
 *
 * Steps run on [executor] (Dispatchers.IO in production, so no commit ever runs on the main thread; a direct executor in tests). [submit] never blocks: it queues
 * the event and kicks a drain; one drainer at a time (an atomic flag), so the executor may be multi-threaded. The state is loaded by [load] on the first step,
 * on the executor too.
 */
class SerialStateMachine<S : Any, E : Any, F : Any>(
    private val load: () -> S,
    private val reduce: (S, E, Long) -> Transition<S, E, F>,
    private val persisted: (S) -> Any?,
    /** Commits the whole snapshot; true = durable. */
    private val save: (S) -> Boolean,
    private val now: () -> Long,
    private val executor: Executor,
    private val prepare: (S, E) -> E = { _, e -> e },
    private val run: (F) -> E? = { null },
    /** Test hook: every reduced event, the time it saw and the state after it (called after the commit, before the effects). */
    private val onStep: (E, Long, S) -> Unit = { _, _, _ -> },
    private val onError: (Throwable) -> Unit = {},
    /** The event a failed save becomes: (the event whose transition could not be saved, its retry attempt). Null = the step fails (the deferred completes exceptionally). */
    private val persistFailed: ((E, Int) -> E)? = null,
    private val persistRecovered: E? = null,
    /** Runs a delayed retry (null = one shared daemon timer thread; tests pass their own). */
    schedule: ((Long, Runnable) -> Unit)? = null,
) {
    private class Job<E>(val event: E, val done: CompletableDeferred<Any?>, val attempt: Int = 0)

    private val queue = ConcurrentLinkedQueue<Job<E>>()
    private val draining = AtomicBoolean(false)
    @Volatile private var current: S? = null
    /** The persisted part last committed; touched only by the drainer. */
    private var saved: Any? = null
    private var failing = false
    private val schedule: (Long, Runnable) -> Unit = schedule ?: { ms, r -> TIMER.schedule(r, ms, java.util.concurrent.TimeUnit.MILLISECONDS) }

    /** The latest committed state; null before the first step loaded it. */
    fun state(): S? = current

    /** Queues [e]; the deferred completes with the reply of its chain (its feedback events and its recheck included). Never blocks, any thread. */
    fun submit(e: E): CompletableDeferred<Any?> {
        val j = Job(e, CompletableDeferred()); queue.add(j); kick(); return j.done
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
            val (reply, recheck) = step(j)
            if (recheck != null) queue.add(Job(recheck, j.done)) else j.done.complete(reply)
        } catch (t: Throwable) { onError(t); j.done.completeExceptionally(t) }
    }

    private fun loadNow() { val s = load(); saved = persisted(s); current = s }

    private fun step(j: Job<E>): Pair<Any?, E?> {
        var reply: Any? = null; var recheck: E? = null
        val pending = ArrayDeque<E>().apply { add(j.event) }
        while (pending.isNotEmpty()) {
            val before = current!!
            val e = prepare(before, pending.removeFirst())
            val t = now()
            var tr = reduce(before, e, t)
            var applied = e
            val p = persisted(tr.state)
            if (p != saved) {
                val ok = try { save(tr.state) } catch (x: Exception) { onError(x); false }
                if (ok) {
                    saved = p
                    if (failing) { failing = false; persistRecovered?.let { pending.add(it) } }
                } else {
                    // Not durable: `tr` is dropped (its state is never published, its effects never run); the failure is an event on the unchanged state.
                    failing = true
                    val fe = (persistFailed ?: throw IllegalStateException("save failed"))(e, j.attempt)
                    tr = reduce(before, fe, t); applied = fe
                    check(persisted(tr.state) == saved) { "a persistence-failure transition must not change the persisted state" }
                }
            }
            current = tr.state
            onStep(applied, t, tr.state)
            if (tr.reply != null) reply = tr.reply
            if (tr.recheck != null) recheck = tr.recheck
            tr.retry?.let { r -> val attempt = j.attempt + 1; schedule(tr.retryAfterMs, Runnable { queue.add(Job(r, CompletableDeferred(), attempt)); kick() }) }
            for (f in tr.effects) {
                val feedback = try { run(f) } catch (x: Exception) { onError(x); null }
                if (feedback != null) pending.add(feedback)
            }
        }
        return reply to recheck
    }

    companion object {
        /** Runs the step on the submitting thread (tests; a machine without disk IO). */
        val DIRECT: Executor = Executor { it.run() }
        private val TIMER: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "serial-machine-retry").apply { isDaemon = true } }
        }
    }
}
