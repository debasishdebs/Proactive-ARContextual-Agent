package com.rokid.context.memory.canvas

import com.rokid.context.memory.db.DeletionEpoch

/**
 * The parts of the deletion fence that do NOT live in the database, read at check time INSIDE the DAO transaction: the in-process wipe generation (`MemoryWipe.generation`)
 * and the persisted deletion epoch. The third part, `frame_forget_rev`, is read from `canvas_meta` in the same transaction. The app passes real readers; tests pass
 * lambdas over a variable ("a test helper bumps the wipe generation").
 */
class HelperFenceLive(val wipeGen: () -> Long, val epoch: () -> DeletionEpoch)

/** The result of `confirmJobFenced`; the names mirror the port's `ConfirmResult` (WP1) one to one so the service maps them without logic. */
enum class HelperConfirmOutcome { CONFIRMED, DUPLICATE, NOT_FOUND, NOT_AWAITING, EXPIRED, MISMATCH, STALE_FENCE }

/**
 * The answer of a lease-holder operation (payload release, heartbeat, result commit). [ok] with [denial] null is success; otherwise [denial] is the protocol denial
 * code of core plan section 6 (`not_found`, `not_confirmed`, `voided`, `cancelled`, `stale_generation`, `lease_expired`, `deadline_exceeded`) the route answers with.
 * [duplicate] marks an idempotent repeat of a committed result.
 */
data class HelperLeaseResult(val ok: Boolean, val denial: String? = null, val leaseUntilMs: Long? = null, val payload: String? = null, val duplicate: Boolean = false)

/** What a lease hands out: the job's metadata, never its payload (that goes through `releasePayload`). `payloadBytes` is the UTF-8 length. */
data class LeasedHelperJob(val jobId: String, val jobType: String, val leaseGen: Long, val leaseUntilMs: Long, val deadlineMs: Long, val bundleHash: String, val payloadBytes: Int)

/** A job row WITHOUT payload or result, for the poll's `available` list and the native confirm card. */
data class HelperJobInfo(
    val jobId: String, val jobType: String, val state: String, val bundleHash: String?, val sessionGen: Long,
    val confirmExpiresAtMs: Long?, val itemCount: Int, val sourceChars: Int, val payloadBytes: Int, val createdAt: Long,
)
