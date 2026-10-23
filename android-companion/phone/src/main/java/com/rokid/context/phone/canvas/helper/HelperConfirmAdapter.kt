package com.rokid.context.phone.canvas.helper

import com.rokid.context.canvas.helper.CanvasLogSink
import com.rokid.context.canvas.helper.ConfirmResult
import com.rokid.context.canvas.helper.HelperJobPort
import com.rokid.context.memory.canvas.ConfirmBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The production [HelperConfirmActions] (core plan WP6, user decision 2026-10-23 evening). The ONLY caller of the job port's `confirm`: a native tap on one pending job
 * becomes a [ConfirmBinding] made from what the PORT reports for that job (job id, immutable bundle hash, session generation, confirmation expiry; the card sends only the job
 * id), is tagged and verified with the memory-only [HelperConfirm] key, and only then reaches the port, whose DAO transaction re-checks state, expiry, hash, session and the
 * deletion fence. A tap with the grant off, after the window, after the key rotated, or on a job that is no longer waiting never reaches the port. No HTTP route holds this
 * class; the key and the tag never leave it (they are not logged, stored or returned).
 *
 * [pending] is a snapshot (the port is suspend): [refresh] fills it, the view model's STARTED-scoped loop calls it every second, and [clear] empties it at a session end.
 */
class HelperConfirmAdapter(
    private val port: HelperJobPort,
    private val confirm: HelperConfirm,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val grantOn: () -> Boolean,
    private val log: CanvasLogSink,
) : HelperConfirmActions {
    @Volatile private var rows: List<PendingConfirmRow> = emptyList()
    private val inflight = CopyOnWriteArrayList<Job>()

    /** Test seam: runs between minting and verifying the tag (a key rotation there must refuse the tap). */
    internal var beforeVerify: () -> Unit = {}

    override fun pending(): List<PendingConfirmRow> = rows

    override suspend fun refresh() {
        rows = try {
            port.pendingConfirmations().map { PendingConfirmRow(it.jobId, "frames", it.itemCount, it.sourceChars, it.expiresAtMs) }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { rows }
    }

    /** The session ended: nothing is pending any more. */
    fun clear() { rows = emptyList() }

    override fun onConfirm(jobId: String) { track(scope.launch { confirmNow(jobId) }) }

    override fun onCancel(jobId: String) {
        track(scope.launch {
            try { port.declineConfirm(jobId) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            refresh()
        })
    }

    private fun track(j: Job) { inflight += j; j.invokeOnCompletion { inflight -= j } }

    /** Waits for every tap and refresh started so far (tests). */
    suspend fun awaitIdle() { for (j in inflight.toList()) j.join() }

    /**
     * The tap, run to completion. Returns the port's answer, or null when the tap was refused before the port (no such waiting job, grant off, bad or expired tag).
     */
    suspend fun confirmNow(jobId: String): ConfirmResult? {
        val job = try { port.pendingConfirmations().firstOrNull { it.jobId == jobId } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (job == null) { refresh(); log.helperLine("confirm refused"); return null }
        if (!grantOn()) { refresh(); log.helperLine("confirm refused"); return null }
        val binding = ConfirmBinding(job.jobId, job.bundleHash, job.sessionGen, job.expiresAtMs)
        val tag = confirm.tag(binding)
        beforeVerify()
        if (!confirm.verify(binding, tag, clock())) { refresh(); return null } // HelperConfirm logs the reason (count-only)
        val result = try { port.confirm(job.jobId, binding) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        refresh()
        return result
    }
}
