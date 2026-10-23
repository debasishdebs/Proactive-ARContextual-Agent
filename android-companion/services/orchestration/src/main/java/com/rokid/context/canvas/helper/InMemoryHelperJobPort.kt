package com.rokid.context.canvas.helper

import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperJobState
import com.rokid.context.memory.canvas.JobEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-memory [HelperJobPort] with the same state machine ([HelperJobState.next]) as the real service, plus a few `fun`s that play the helper (lease, complete,
 * fail). For tests of the executor, the adapter and the UI (the real service, WP5, must pass the same contract). Clock, session generation and fence are injected.
 * The fake models the confirmation window ([HelperProtocol.CONFIRM_TTL_MS]) and the queue wait ([HelperProtocol.QUEUE_WAIT_MS]) through [tick] and every call.
 */
class InMemoryHelperJobPort(
    private val now: () -> Long = System::currentTimeMillis,
    private val sessionGen: () -> Long = { 1L },
    private val newId: () -> String = Counter()::next,
    /** The current fence; null = no deletion can happen. A confirm after it moved voids the job (stale confirm after Forget). */
    private val fenceNow: (() -> HelperFence)? = null,
) : HelperJobPort {
    private class Counter { private var n = 0; fun next() = "job-${++n}" }

    private class Job(
        val id: String, val payload: JsonObject, val bundleHash: String, val sessionGen: Long, val expiresAtMs: Long, val fence: HelperFence,
        val itemCount: Int, val sourceChars: Int, var state: HelperJobState, var stateSince: Long,
    ) {
        val outcome = CompletableDeferred<PyOutcome>()
        fun binding() = ConfirmBinding(id, bundleHash, sessionGen, expiresAtMs)
    }

    private val lock = Any()
    private val jobs = LinkedHashMap<String, Job>()

    override suspend fun runPy(req: PyRequest): PyOutcome {
        val id = when (val r = submit(req)) { is Submitted.Ok -> r.id; is Submitted.Bad -> return PyOutcome.Refused(r.reason); Submitted.Stale -> return PyOutcome.Voided }
        val job = synchronized(lock) { jobs.getValue(id) }
        try {
            return job.outcome.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { cancel(id) }
            throw e
        }
    }

    private sealed class Submitted { class Ok(val id: String) : Submitted(); class Bad(val reason: String) : Submitted(); object Stale : Submitted() }

    private fun submit(req: PyRequest): Submitted {
        val payload = req.payload()
        val errs = HelperProtocol.validatePayload(payload)
        if (errs.isNotEmpty()) return Submitted.Bad(errs.first())
        val hash = try { BundleHash.of(payload) } catch (_: IllegalArgumentException) { return Submitted.Bad("payload is not canonical") }
        // A Forget or Delete all since the runner captured the fence: no job at all (the real service refuses the insert the same way).
        if (fenceNow != null && !req.fence.holds(fenceNow.invoke())) return Submitted.Stale
        synchronized(lock) {
            val t = now()
            val job = Job(newId(), payload, hash, sessionGen(), t + HelperProtocol.CONFIRM_TTL_MS, req.fence, req.items.size, req.source.length, HelperJobState.AWAITING_CONFIRM, t)
            jobs[job.id] = job
            return Submitted.Ok(job.id)
        }
    }

    /** Creates a job without waiting for its outcome (tests). */
    suspend fun submitForTest(req: PyRequest): String = (submit(req) as Submitted.Ok).id

    override suspend fun awaitingConfirm(jobId: String): Boolean = synchronized(lock) { tickLocked(); jobs[jobId]?.state == HelperJobState.AWAITING_CONFIRM }

    override suspend fun confirm(jobId: String, binding: ConfirmBinding): ConfirmResult = synchronized(lock) {
        val job = jobs[jobId] ?: return ConfirmResult.NOT_FOUND
        if (binding == job.binding() && (job.state == HelperJobState.QUEUED || job.state == HelperJobState.LEASED)) return ConfirmResult.DUPLICATE
        if (job.state != HelperJobState.AWAITING_CONFIRM) return ConfirmResult.NOT_AWAITING
        if (binding != job.binding()) return ConfirmResult.MISMATCH
        if (now() >= job.expiresAtMs) { finish(job, JobEvent.EXPIRE); return ConfirmResult.EXPIRED }
        if (sessionGen() != job.sessionGen) { finish(job, JobEvent.VOID); return ConfirmResult.STALE_FENCE }
        if (fenceNow != null && !job.fence.holds(fenceNow.invoke())) { finish(job, JobEvent.VOID); return ConfirmResult.STALE_FENCE }
        finish(job, JobEvent.CONFIRM)
        ConfirmResult.CONFIRMED
    }

    override suspend fun declineConfirm(jobId: String): Boolean = synchronized(lock) {
        val job = jobs[jobId]?.takeIf { it.state == HelperJobState.AWAITING_CONFIRM } ?: return false
        finish(job, JobEvent.DECLINE)
    }

    override suspend fun pendingConfirmations(): List<PendingConfirmation> = synchronized(lock) {
        tickLocked()
        jobs.values.filter { it.state == HelperJobState.AWAITING_CONFIRM }.map { PendingConfirmation(it.id, it.bundleHash, it.sessionGen, it.expiresAtMs, it.itemCount, it.sourceChars) }
    }

    // ---- the fake helper and the clock ----

    fun state(jobId: String): HelperJobState? = synchronized(lock) { jobs[jobId]?.state }

    fun outcomeOf(jobId: String): PyOutcome? = synchronized(lock) { jobs[jobId]?.outcome?.takeIf { it.isCompleted }?.getCompleted() }

    /** Expires confirmation windows and queue waits against the injected clock. */
    fun tick() = synchronized(lock) { tickLocked() }

    private fun tickLocked() {
        val t = now()
        for (j in jobs.values.toList()) when (j.state) {
            HelperJobState.AWAITING_CONFIRM -> if (t >= j.expiresAtMs) finish(j, JobEvent.EXPIRE)
            HelperJobState.QUEUED -> if (t >= j.stateSince + HelperProtocol.QUEUE_WAIT_MS) finish(j, JobEvent.EXPIRE)
            HelperJobState.LEASED -> if (t >= j.stateSince + HelperProtocol.PY_EXEC_DEADLINE_MS) finish(j, JobEvent.EXPIRE)
            else -> Unit
        }
    }

    /** The helper's lease: only a QUEUED (so confirmed) job, and only one active job (maxActiveJobs). */
    fun leaseNext(): String? = synchronized(lock) {
        tickLocked()
        if (jobs.values.any { it.state == HelperJobState.LEASED }) return null
        val job = jobs.values.firstOrNull { it.state == HelperJobState.QUEUED } ?: return null
        finish(job, JobEvent.LEASE)
        job.id
    }

    /** The helper posts a result (the exact text and its claimed hash, as on the wire). Returns the refusal reasons (empty = committed). The job stays LEASED when refused. */
    fun complete(jobId: String, resultCanonical: String, resultHash: String): List<String> = synchronized(lock) {
        tickLocked() // a result at or after lease + pyExecDeadlineMs is expired, not accepted
        val job = jobs[jobId]?.takeIf { it.state == HelperJobState.LEASED } ?: return listOf("job is not leased")
        // the same order as the real service: size, the hash over the text as received, the parse, then the shape against the payload
        val result = when (val c = ResultWire.check(resultCanonical, resultHash)) {
            is ResultWire.Checked.Rejected -> return listOf(if (c.kind == ResultWire.Kind.TOO_LARGE) "too_large" else c.reason)
            is ResultWire.Checked.Ok -> c.result
        }
        val errs = HelperProtocol.validateResult(job.payload, result)
        if (errs.isNotEmpty()) return errs
        finish(job, JobEvent.RESULT, result = result)
        emptyList()
    }

    /** The helper reports a failure. A moved fence voids the job and an expired job deadline ends it `timeout`: both answer false (the code is never acknowledged for a stale job). */
    fun fail(jobId: String, code: String): Boolean = synchronized(lock) {
        val job = jobs[jobId]?.takeIf { it.state == HelperJobState.LEASED && code in HelperProtocol.FAIL_CODES } ?: return false
        if (fenceNow != null && !job.fence.holds(fenceNow.invoke())) { finish(job, JobEvent.VOID); return false }
        tickLocked()
        if (job.state != HelperJobState.LEASED) return false
        finish(job, JobEvent.FAIL, code)
    }

    fun cancel(jobId: String): Boolean = synchronized(lock) { jobs[jobId]?.takeIf { it.state.open }?.let { finish(it, JobEvent.CANCEL) } ?: false }

    /** Every deletion cause, grant off, Disconnect and session end: all open jobs end VOIDED. */
    fun voidAll() = synchronized(lock) { jobs.values.filter { it.state.open }.forEach { finish(it, JobEvent.VOID) } }

    private fun finish(job: Job, event: JobEvent, failCode: String? = null, result: JsonObject? = null): Boolean {
        val before = job.state
        val next = before.next(event) ?: return false
        job.state = next; job.stateSince = now()
        if (!next.open) job.outcome.complete(when {
            next == HelperJobState.DONE -> done(result!!)
            next == HelperJobState.VOIDED -> PyOutcome.Voided
            next == HelperJobState.ABORTED -> PyOutcome.Cancelled
            event == JobEvent.CANCEL -> PyOutcome.Cancelled
            event == JobEvent.DECLINE || (event == JobEvent.EXPIRE && before == HelperJobState.AWAITING_CONFIRM) -> PyOutcome.NotConfirmed
            event == JobEvent.EXPIRE && before == HelperJobState.LEASED -> PyOutcome.Failed("timeout")
            event == JobEvent.EXPIRE -> PyOutcome.Failed("resources_busy")
            else -> PyOutcome.Failed(failCode ?: "runtime_error")
        })
        return true
    }

    private fun done(result: JsonObject): PyOutcome {
        val outs = (result.getValue("outputs") as JsonArray).map {
            val o = it.jsonObject
            val ok = o.getValue("ok").jsonPrimitive.content == "true"
            PyItemOutput(o.getValue("id").jsonPrimitive.content, ok, o["output"], o["code"]?.jsonPrimitive?.content)
        }
        return PyOutcome.Done(outs, result.getValue("stats").jsonObject.getValue("ms").jsonPrimitive.content.toLong())
    }
}
