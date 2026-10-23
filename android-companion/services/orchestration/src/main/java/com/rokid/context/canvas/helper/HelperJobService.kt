package com.rokid.context.canvas.helper

import com.rokid.context.core.log.Level
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperConfirmOutcome
import com.rokid.context.memory.canvas.HelperJobInfo
import com.rokid.context.memory.canvas.HelperJobState
import com.rokid.context.memory.canvas.HelperLeaseResult
import com.rokid.context.memory.canvas.LeasedHelperJob
import com.rokid.context.memory.db.CanvasHelperCleanupEntity
import com.rokid.context.memory.db.CanvasHelperJobEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The answer to a result post (the route maps it: `Committed` 200, `Denied` the denial code, `Invalid` 422 `invalid_result`, `TooLarge` 413). */
sealed class ResultCommit {
    data class Committed(val duplicate: Boolean) : ResultCommit()
    data class Denied(val code: String) : ResultCommit()
    data class Invalid(val reasons: List<String>) : ResultCommit()
    object TooLarge : ResultCommit()
}

/**
 * The one job service (core plan WP5), modelled on `ReplayService`: one persisted job life per `py_exec` run, one terminal fan-out, waiters that complete exactly once.
 * It implements [HelperJobPort] (the executor's side: [runPy] and the confirmation operations) and carries the helper's side as plain methods ([lease], [releasePayload],
 * [heartbeat], [commitResult], [failJob], [pendingCleanup], [advertiseReady]) for the routes of WP6. Everything durable goes through the injected [HelperJobStore] (the WP3
 * fenced DAO); the clock, the session generation and the readiness store are injected too. It never sees the HMAC key: [confirm] receives the already verified
 * [ConfirmBinding] and forwards it. It depends on no `:phone` class and logs only count-only lines through [log].
 *
 * Three SEPARATE bounded waits (c8 #2; they replace the single submission-to-terminal timer of the old design):
 *  1. confirmation, [HelperProtocol.CONFIRM_TTL_MS] from submit (the row's `confirmExpiresAtMs`);
 *  2. queue, [HelperProtocol.QUEUE_WAIT_MS] from the confirm to the lease (the row's `confirmedAtMs`); expiry ends the job `resources_busy`;
 *  3. execution, [HelperProtocol.EXEC_WAIT_MS] from the lease (the job's own deadline is [HelperProtocol.PY_EXEC_DEADLINE_MS] from the lease, the 5 s are the waiter's slack).
 * Each ends the job through a terminal transition that nulls the payload and enqueues the cleanup row (the DAO does both in the same transaction).
 *
 * [sweep] expires every overdue job and completes every waiter whose row is terminal; [runPy] drives it every [waiterPollMs] while it waits, so a run is bounded even when
 * nobody else sweeps (WP6 may also call it on a timer, and after any deletion hook: a job closed outside this class is seen at the next sweep).
 */
class HelperJobService(
    private val store: HelperJobStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionGen: () -> Long = { 1L },
    private val readiness: HelperReadinessStore = InMemoryReadinessStore(),
    private val log: CanvasLogSink = CanvasLogSink { _, _, _ -> },
    private val newId: () -> String = { "hj_" + UUID.randomUUID().toString().replace("-", "").take(20) },
    private val waiterPollMs: Long = 250L,
) : HelperJobPort {
    private class Waiter { val outcome = CompletableDeferred<PyOutcome>() }

    private val waiters = ConcurrentHashMap<String, Waiter>()

    /** Number of runs currently waiting (tests, status). */
    fun waiterCount(): Int = waiters.size

    private fun line(msg: String) { try { log.log(Level.I, "canvas", msg) } catch (_: Throwable) { } }

    // ---- the executor's side (HelperJobPort) ----

    override suspend fun runPy(req: PyRequest): PyOutcome {
        val payload = req.payload()
        val errs = HelperProtocol.validatePayload(payload)
        if (errs.isNotEmpty()) return PyOutcome.Refused(errs.first())
        val canonical = try { BundleHash.canonical(payload) } catch (_: IllegalArgumentException) { return PyOutcome.Refused("payload is not canonical") }
        val t = clock()
        val id = newId()
        val job = CanvasHelperJobEntity(
            jobId = id, capability = HelperProtocol.JOB_TYPE_PY_EXEC, state = HelperJobState.AWAITING_CONFIRM.name,
            frameIdsJson = JsonArray(req.frameIds.map { JsonPrimitive(it) }).toString(), createdAt = t, updatedAt = t, jobType = HelperProtocol.JOB_TYPE_PY_EXEC,
            sessionGen = sessionGen(), bundleHash = BundleHash.ofCanonical(canonical), payloadJson = canonical, confirmExpiresAtMs = t + HelperProtocol.CONFIRM_TTL_MS,
            itemCount = req.items.size, sourceChars = req.source.length,
        )
        try {
            // A Forget, Delete all or restart since the runner captured the fence: no row, nothing to clean up.
            if (!store.insert(job, req.fence)) { line("job refused by fence"); return PyOutcome.Voided }
            // The waiter exists only once the row does: a concurrent sweep can never see "no row" for a job that is about to be inserted. A job closed between the insert
            // and this registration is picked up from its terminal row by settle.
            val w = Waiter()
            waiters[id] = w
            line("job awaiting confirmation")
            settle(id)
            while (true) {
                val done = withTimeoutOrNull(waiterPollMs) { w.outcome.await() }
                if (done != null) return done
                sweep()
            }
        } catch (e: Throwable) {
            // cancellation (the executor's coroutine) or any failure after the insert may have committed: the job must not outlive its run
            withContext(NonCancellable) { runCatching { cancel(id) }; waiters.remove(id) }
            throw e
        }
    }

    override suspend fun awaitingConfirm(jobId: String): Boolean {
        sweep()
        return store.job(jobId)?.state == HelperJobState.AWAITING_CONFIRM.name
    }

    override suspend fun confirm(jobId: String, binding: ConfirmBinding): ConfirmResult {
        // No sweep first: a tap after the window must answer EXPIRED (and end the job NOT_CONFIRMED), not NOT_AWAITING.
        val now = clock()
        val row = store.job(jobId)
        // A job of another helper session (the session ended since the submit) can never be released: void it.
        if (row != null && row.state == HelperJobState.AWAITING_CONFIRM.name && binding == ConfirmBinding(row.jobId, row.bundleHash.orEmpty(), row.sessionGen, row.confirmExpiresAtMs ?: 0L) && now < (row.confirmExpiresAtMs ?: 0L) && row.sessionGen != sessionGen()) {
            store.void(jobId, "session", now); settle(jobId); return ConfirmResult.STALE_FENCE
        }
        val r = when (store.confirm(jobId, binding, now)) {
            HelperConfirmOutcome.CONFIRMED -> ConfirmResult.CONFIRMED
            HelperConfirmOutcome.DUPLICATE -> ConfirmResult.DUPLICATE
            HelperConfirmOutcome.NOT_FOUND -> ConfirmResult.NOT_FOUND
            HelperConfirmOutcome.NOT_AWAITING -> ConfirmResult.NOT_AWAITING
            HelperConfirmOutcome.EXPIRED -> ConfirmResult.EXPIRED
            HelperConfirmOutcome.MISMATCH -> ConfirmResult.MISMATCH
            HelperConfirmOutcome.STALE_FENCE -> ConfirmResult.STALE_FENCE
        }
        if (r == ConfirmResult.EXPIRED || r == ConfirmResult.STALE_FENCE) settle(jobId)
        line("confirm " + r.name.lowercase())
        return r
    }

    override suspend fun declineConfirm(jobId: String): Boolean = store.decline(jobId, clock()).also { if (it) { settle(jobId); line("confirm declined") } }

    override suspend fun pendingConfirmations(): List<PendingConfirmation> {
        sweep()
        return store.awaiting().map { PendingConfirmation(it.jobId, it.bundleHash.orEmpty(), it.sessionGen, it.confirmExpiresAtMs ?: 0L, it.itemCount, it.sourceChars) }
    }

    // ---- the helper's side (routes of WP6) ----

    /** The poll's `available`: confirmed, queued jobs of the current session; never an AWAITING_CONFIRM job. */
    suspend fun available(types: List<String>): List<HelperJobInfo> { sweep(); return store.available(types, sessionGen()) }

    /** The lease: one confirmed job to [helperId], at most one LEASED job per helper. Overdue jobs are expired first. */
    suspend fun lease(helperId: String, types: List<String>): LeasedHelperJob? {
        sweep()
        val l = store.lease(types, helperId, sessionGen(), clock(), HelperProtocol.LEASE_MS, HelperProtocol.PY_EXEC_DEADLINE_MS, HelperProtocol.QUEUE_WAIT_MS)
        if (l != null) line("job leased")
        settleAll() // a candidate whose fence moved was voided inside the lease transaction
        return l
    }

    suspend fun releasePayload(jobId: String, helperId: String, leaseGen: Long): HelperLeaseResult =
        store.releasePayload(jobId, helperId, leaseGen, clock()).also { afterLeaseCall(jobId, it, terminalOnOk = false) }

    suspend fun heartbeat(jobId: String, helperId: String, leaseGen: Long): HelperLeaseResult =
        store.heartbeat(jobId, helperId, leaseGen, clock(), HelperProtocol.LEASE_MS).also { afterLeaseCall(jobId, it, terminalOnOk = false) }

    /**
     * Commits the helper's result (decision D1, 2026-10-24). [resultCanonical] is the exact TEXT the helper hashed and [resultHash] its claimed SHA-256: [ResultWire.check] verifies the hash over
     * the text AS RECEIVED, then parses it (duplicate keys and invalid JSON are `Invalid`), then the shape and every output member are checked against the stored payload's `outSchema`
     * (floats pass `number`, fail `integer`). An invalid result changes nothing and the lease stays; the DAO then commits in one fenced transaction. The stored hash (the idempotency key of a repeat) is the
     * verified one, and the stored text is the received text.
     */
    suspend fun commitResult(jobId: String, helperId: String, leaseGen: Long, resultCanonical: String, resultHash: String): ResultCommit {
        val result = when (val c = ResultWire.check(resultCanonical, resultHash)) {
            is ResultWire.Checked.Rejected -> return if (c.kind == ResultWire.Kind.TOO_LARGE) ResultCommit.TooLarge else ResultCommit.Invalid(listOf(c.reason))
            is ResultWire.Checked.Ok -> c.result
        }
        val row = store.job(jobId)
        val payload = row?.takeIf { it.state == HelperJobState.LEASED.name && it.leaseOwner == helperId && it.leaseGen == leaseGen }?.payloadJson
        if (payload != null) {
            val errs = HelperProtocol.validateResult(Json.parseToJsonElement(payload), result)
            if (errs.isNotEmpty()) return ResultCommit.Invalid(errs)
        }
        val r = store.commit(jobId, helperId, leaseGen, resultHash, resultCanonical, clock())
        afterLeaseCall(jobId, r, terminalOnOk = true)
        return if (r.ok) ResultCommit.Committed(r.duplicate) else ResultCommit.Denied(r.denial ?: "stale_generation")
    }

    /** The helper reports a failure ([code] of the fail enum). False for an unknown code or a caller that is not the live lease holder. */
    suspend fun failJob(jobId: String, helperId: String, leaseGen: Long, code: String): Boolean {
        if (code !in HelperProtocol.FAIL_CODES) return false
        val ok = store.fail(jobId, helperId, leaseGen, code, clock())
        settle(jobId)
        if (ok) line("job failed by helper")
        return ok
    }

    /** After a lease-holder call: a denial or a closed job means the row may have moved to a terminal state (voided by the fence, expired): close overdue jobs and settle. */
    private suspend fun afterLeaseCall(jobId: String, r: HelperLeaseResult, terminalOnOk: Boolean) {
        if (!r.ok) sweep() else if (terminalOnOk) settle(jobId)
    }

    // ---- cancel, void, terminal fan-out ----

    /** Cancels one open job (any state): payload nulled, cleanup enqueued, the waiter completes with [PyOutcome.Cancelled]. False when it was not open. */
    suspend fun cancel(jobId: String): Boolean = store.cancel(jobId, clock()).also { settle(jobId); if (it) line("job cancelled") }

    suspend fun voidJob(jobId: String, reason: String): Boolean = store.void(jobId, reason, clock()).also { settle(jobId); if (it) line("job voided") }

    /** Every open job ends VOIDED (grant off, Disconnect, session end, developer mode off). Returns how many. */
    suspend fun voidAll(reason: String): Int = store.voidAll(reason, clock()).also { settleAll(); if (it > 0) line("jobs voided " + it) }

    // ---- expiry sweep and waiter completion ----

    /** Expires every overdue confirmation window, queue wait and lease/deadline, then completes the waiter of every job that is terminal (including jobs closed outside this class). */
    suspend fun sweep() {
        val t = clock()
        val a = store.expireAwaitingConfirm(t).size
        val q = store.expireQueued(t, HelperProtocol.QUEUE_WAIT_MS).size
        val l = store.expireLeases(t).size
        if (a + q + l > 0) line("jobs expired " + (a + q + l))
        settleAll(t)
    }

    private suspend fun settleAll(t: Long = clock()) { for (id in waiters.keys.toList()) settle(id, t) }

    /** Completes the waiter of [jobId] exactly once, from the persisted row; a leased job whose execution wait ran out ends here (the backstop of the job deadline). */
    private suspend fun settle(jobId: String, t: Long = clock()) {
        val w = waiters[jobId] ?: return
        var row = store.job(jobId)
        var forced: PyOutcome? = null
        if (row != null && row.state == HelperJobState.LEASED.name && execWaitEnd(row) <= t) {
            // wait 3 ran out although the job deadline (130 s, enforced by the DAO) did not close it: the job ends VOIDED with its cleanup and the run fails `timeout`
            store.void(jobId, "wait_exceeded", t)
            row = store.job(jobId)
            forced = PyOutcome.Failed("timeout")
        }
        val outcome = forced ?: if (row == null) PyOutcome.Voided else outcomeOf(row) ?: return
        if (waiters.remove(jobId, w)) w.outcome.complete(outcome)
    }

    /**
     * End of wait 3 (execution): [HelperProtocol.EXEC_WAIT_MS] from the LEASE (the row stores the lease time as `deadlineMs - pyExecDeadlineMs`), never from the submit. Waits 1 and 2
     * need no timer here: the DAO ends an unconfirmed job at its `confirmExpiresAtMs` (from submit) and a queued one at `confirmedAtMs + queueWaitMs` (from the confirm), in [sweep].
     */
    private fun execWaitEnd(r: CanvasHelperJobEntity): Long = (r.deadlineMs?.minus(HelperProtocol.PY_EXEC_DEADLINE_MS) ?: r.updatedAt) + HelperProtocol.EXEC_WAIT_MS

    /** The executor's outcome of a terminal row, or null while the job is open. Mirrors the in-memory fake exactly. */
    private fun outcomeOf(r: CanvasHelperJobEntity): PyOutcome? = when (HelperJobState.valueOf(r.state)) {
        HelperJobState.AWAITING_CONFIRM, HelperJobState.QUEUED, HelperJobState.LEASED -> null
        HelperJobState.DONE -> if (r.resultJson == null && r.voidReason == "frame_forget") PyOutcome.Voided else done(r.resultJson)
        HelperJobState.VOIDED -> PyOutcome.Voided
        HelperJobState.ABORTED -> PyOutcome.Cancelled
        HelperJobState.CANCELLED -> if (r.voidReason == "declined") PyOutcome.NotConfirmed else PyOutcome.Cancelled
        HelperJobState.FAILED -> when (val c = r.failCode) {
            "not_confirmed" -> PyOutcome.NotConfirmed
            "deadline_exceeded" -> PyOutcome.Failed("timeout")
            "lease_expired" -> PyOutcome.Failed("killed")
            null -> PyOutcome.Failed("runtime_error")
            else -> PyOutcome.Failed(c)
        }
    }

    private fun done(resultJson: String?): PyOutcome = try {
        val result = Json.parseToJsonElement(resultJson ?: return PyOutcome.Failed("bad_output")).jsonObject
        PyOutcome.Done(
            result.getValue("outputs").jsonArray.map {
                val o = it.jsonObject
                PyItemOutput(o.getValue("id").jsonPrimitive.content, o.getValue("ok").jsonPrimitive.content == "true", o["output"], o["code"]?.jsonPrimitive?.content)
            },
            result.getValue("stats").jsonObject.getValue("ms").jsonPrimitive.content.toLong(),
        )
    } catch (_: Exception) { PyOutcome.Failed("bad_output") }

    // ---- cleanup commands (the poll) ----

    suspend fun pendingCleanup(limit: Int = 50): List<CanvasHelperCleanupEntity> = store.pendingCleanup(limit)
    suspend fun ackCleanup(ids: List<Long>): Int = store.ackCleanup(ids)

    // ---- readiness ----

    /** The helper's `ready` field of a poll: the last advertisement for `py_exec`, kept in memory. */
    fun advertiseReady(ready: Boolean) = readiness.record(HelperProtocol.JOB_TYPE_PY_EXEC, ready, clock())

    /** Ready only for a fresh `true`; no advertisement within [HelperProtocol.READY_STALE_MS] reads as stale and not ready. */
    fun readiness(): Readiness {
        val rec = readiness.read(HelperProtocol.JOB_TYPE_PY_EXEC) ?: return Readiness(false, true, null)
        val age = clock() - rec.atMs
        val stale = age >= HelperProtocol.READY_STALE_MS
        return Readiness(rec.ready && !stale, stale, age)
    }

}
