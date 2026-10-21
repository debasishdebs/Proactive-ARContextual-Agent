package com.rokid.context.work.frames.intent

import com.rokid.context.ai.contract.IntentOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** A photo that may be classified: its id and when it was captured (phone clock; `capturedAtMs ?: frame.ts`, null = unknown). */
data class IntentFrame(val frameId: String, val capturedAtMs: Long?)

/** What the sampler needs from storage (production: Room; tests: a fake). */
interface IntentFrameStore {
    /** The newest unlabelled photo received at or after [sinceReceivedMs]. */
    suspend fun newestUnlabelled(sinceReceivedMs: Long): IntentFrame?
    /** Rows changed: 1 = stored, 0 = the frame is gone. */
    suspend fun setIntent(frameId: String, label: String, conf: Float, at: Long): Int
    suspend fun tombstoned(frameId: String): Boolean
}

/**
 * Image intent, at most one photo in flight (spec 2026-10-21 §6.5/§7.3, amended): runs only inside a vehicle episode ([episode], which the
 * phone derives from a listed Bluetooth device), at most once a minute, on a photo captured at most 2 minutes ago. No background probe.
 *
 * The result is annotation and a driver/passenger vote: it never pauses or throttles capture. Fences (final review, Codex): admission AND
 * completion check the source switch ([enabled]), the pipeline ([pipelineOn]), the wipe [generation] and the forget tombstone; the
 * shared power/thermal/battery gate ([admit], which may take a shared allowance) is asked last at admission only. A result is stored
 * with [IntentFrameStore.setIntent] and evidence is emitted only if exactly one row changed. A photo whose capture time is unknown,
 * in the future or older than the window (a backlog flushed late) is never classified and never feeds fusion: the receive time is not the
 * capture time. [poke] never blocks; [close] cancels the call in flight.
 */
class IntentSampler(
    private val scope: CoroutineScope,
    private val store: IntentFrameStore,
    private val classify: suspend (frameId: String) -> IntentOutput?,
    private val clock: () -> Long,
    private val episode: () -> Boolean,
    private val onResult: (frameId: String, capturedAtMs: Long, out: IntentOutput) -> Unit,
    private val enabled: () -> Boolean,
    /** true = a result stored, false = a failure, null = skipped (or dropped by a fence). */
    private val stats: (ok: Boolean?) -> Unit,
    private val admit: () -> Boolean = { true },
    private val pipelineOn: () -> Boolean = { true },
    private val generation: () -> Long = { 0L },
) {
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var job: Job? = null
    @Volatile private var lastCallAt = Long.MIN_VALUE / 2

    /** A frame was stored. Cheap and non-blocking; the work runs on [scope]. */
    fun poke() {
        if (closed || !enabled()) return
        if (!busy.compareAndSet(false, true)) { stats(null); return }
        val j = scope.launch { run() }
        job = j
        j.invokeOnCompletion { busy.set(false) }
    }

    /** Cancels the call in flight; a closed sampler never calls again. */
    fun close() { closed = true; job?.cancel() }

    private fun live(gen: Long) = !closed && enabled() && pipelineOn() && generation() == gen

    private suspend fun run() {
        try {
            val gen = generation()
            if (!live(gen) || !episode()) { stats(null); return }
            val now = clock()
            if (now - lastCallAt < MIN_GAP_MS) { stats(null); return }
            val f = store.newestUnlabelled(now - RECENT_MS)
            val cap = f?.capturedAtMs
            if (f == null || cap == null || cap < now - RECENT_MS || cap > now + SKEW_MS || store.tombstoned(f.frameId)) { stats(null); return }
            if (!admit()) { stats(null); return }
            lastCallAt = now
            val out = try { classify(f.frameId) } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            if (out == null) { stats(false); return }
            if (!live(gen) || store.tombstoned(f.frameId)) { stats(null); return }
            if (store.setIntent(f.frameId, out.label.name, out.confidence, clock()) != 1) { stats(null); return }
            stats(true)
            if (live(gen) && cap >= clock() - FEED_MAX_AGE_MS) onResult(f.frameId, cap, out)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            stats(false)
        }
    }

    companion object {
        const val MIN_GAP_MS = 60_000L
        const val RECENT_MS = 120_000L
        const val SKEW_MS = 30_000L
        const val FEED_MAX_AGE_MS = 600_000L
    }
}
