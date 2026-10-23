package com.rokid.context.canvas.helper

import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperConfirmOutcome
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperFenceLive
import com.rokid.context.memory.canvas.HelperJobInfo
import com.rokid.context.memory.canvas.HelperLeaseResult
import com.rokid.context.memory.canvas.LeasedHelperJob
import com.rokid.context.memory.db.CanvasHelperCleanupEntity
import com.rokid.context.memory.db.CanvasHelperJobEntity
import com.rokid.context.memory.db.CanvasSlice3Dao

/**
 * The narrow facade of the WP3 job DAO that [HelperJobService] depends on (injected, so the service holds no Room type beyond the two row classes and the fence readers
 * live in the adapter). Every method is one DAO transaction; the service adds no SQL. [DaoHelperJobStore] is the production binding.
 */
interface HelperJobStore {
    suspend fun insert(job: CanvasHelperJobEntity, fence: HelperFence): Boolean
    suspend fun job(id: String): CanvasHelperJobEntity?
    suspend fun confirm(jobId: String, binding: ConfirmBinding, now: Long): HelperConfirmOutcome
    suspend fun decline(jobId: String, now: Long): Boolean
    suspend fun cancel(jobId: String, now: Long): Boolean
    suspend fun void(jobId: String, reason: String, now: Long): Boolean
    suspend fun voidAll(reason: String, now: Long): Int
    suspend fun expireAwaitingConfirm(now: Long): List<String>
    suspend fun expireQueued(now: Long, queueWaitMs: Long): List<String>
    suspend fun expireLeases(now: Long): List<String>
    suspend fun lease(types: List<String>, helperId: String, sessionGen: Long, now: Long, leaseMs: Long, jobDeadlineMs: Long, queueWaitMs: Long): LeasedHelperJob?
    suspend fun releasePayload(jobId: String, helperId: String, leaseGen: Long, now: Long): HelperLeaseResult
    suspend fun heartbeat(jobId: String, helperId: String, leaseGen: Long, now: Long, leaseMs: Long): HelperLeaseResult
    suspend fun commit(jobId: String, helperId: String, leaseGen: Long, resultHash: String, resultJson: String, now: Long): HelperLeaseResult
    suspend fun fail(jobId: String, helperId: String, leaseGen: Long, code: String, now: Long): Boolean
    suspend fun awaiting(): List<HelperJobInfo>
    suspend fun available(types: List<String>, sessionGen: Long): List<HelperJobInfo>
    suspend fun pendingCleanup(limit: Int): List<CanvasHelperCleanupEntity>
    suspend fun ackCleanup(ids: List<Long>): Int
}

/** Production [HelperJobStore]: forwards to [CanvasSlice3Dao], passing the injected [HelperFenceLive] readers (wipe generation, deletion epoch) to every fenced call. */
class DaoHelperJobStore(private val dao: CanvasSlice3Dao, private val live: HelperFenceLive) : HelperJobStore {
    override suspend fun insert(job: CanvasHelperJobEntity, fence: HelperFence) = dao.insertHelperJobFenced(job, fence, live)
    override suspend fun job(id: String) = dao.helperJob(id)
    override suspend fun confirm(jobId: String, binding: ConfirmBinding, now: Long) = dao.confirmJobFenced(jobId, binding, live, now)
    override suspend fun decline(jobId: String, now: Long) = dao.declineConfirm(jobId, now)
    override suspend fun cancel(jobId: String, now: Long) = dao.cancelHelperJob(jobId, now)
    override suspend fun void(jobId: String, reason: String, now: Long) = dao.voidHelperJob(jobId, reason, now)
    override suspend fun voidAll(reason: String, now: Long) = dao.voidAllHelperJobs(reason, now)
    override suspend fun expireAwaitingConfirm(now: Long) = dao.expireAwaitingConfirm(now)
    override suspend fun expireQueued(now: Long, queueWaitMs: Long) = dao.expireQueuedHelperJobs(now, queueWaitMs)
    override suspend fun expireLeases(now: Long) = dao.expireHelperLeases(now)
    override suspend fun lease(types: List<String>, helperId: String, sessionGen: Long, now: Long, leaseMs: Long, jobDeadlineMs: Long, queueWaitMs: Long) =
        dao.leaseHelperJob(types, helperId, sessionGen, now, leaseMs, jobDeadlineMs, queueWaitMs, live)
    override suspend fun releasePayload(jobId: String, helperId: String, leaseGen: Long, now: Long) = dao.releasePayload(jobId, helperId, leaseGen, now, live)
    override suspend fun heartbeat(jobId: String, helperId: String, leaseGen: Long, now: Long, leaseMs: Long) = dao.heartbeatHelperJob(jobId, helperId, leaseGen, now, leaseMs, live)
    override suspend fun commit(jobId: String, helperId: String, leaseGen: Long, resultHash: String, resultJson: String, now: Long) =
        dao.commitHelperResultFenced(jobId, helperId, leaseGen, resultHash, resultJson, now, live)
    override suspend fun fail(jobId: String, helperId: String, leaseGen: Long, code: String, now: Long) = dao.failHelperJob(jobId, helperId, leaseGen, code, now, live)
    override suspend fun awaiting() = dao.awaitingConfirmJobs()
    override suspend fun available(types: List<String>, sessionGen: Long) = dao.availableHelperJobs(types, sessionGen)
    override suspend fun pendingCleanup(limit: Int) = dao.pendingCleanup(limit)
    override suspend fun ackCleanup(ids: List<Long>) = dao.ackCleanup(ids)
}
