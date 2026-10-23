package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import com.rokid.context.memory.canvas.ConfirmBinding
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperFenceLive

/**
 * Shared fixture of the WP3 helper-job DAO tests: an in-memory v8 database, a controllable wipe generation, deletion epoch and clock, and builders for a
 * `py_exec` job. The revision is advanced DIRECTLY ([bumpRev], [wipe], [newEpoch]) and no deletion hook is ever called (core plan section 4.1, WP3).
 */
internal class HelperJobFixture {
    val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    val dao = db.canvasSlice3Dao()

    var generation = 5L
    var epoch = DeletionEpoch("install-a", 3, 1_000)
    var now = 1_000_000L
    val live = HelperFenceLive({ generation }, { epoch })

    suspend fun fence() = HelperFence(generation, epoch, dao.frameForgetRev())

    /** A Forget of any frame, reduced to what the fence sees: the revision counter moves (no deletion hook runs). */
    suspend fun bumpRev() = dao.bumpFrameForgetRev()
    /** A Delete all, reduced to what the fence sees: the in-process generation moves. */
    fun wipe() { generation += 1 }
    /** A Delete all of an earlier process, reduced to what the fence sees: the persisted counter moves. */
    fun newEpoch() { epoch = DeletionEpoch(epoch.installId, epoch.counter + 1, now) }

    fun job(id: String, payload: String = "{\"p\":\"$id\"}", sessionGen: Long = 7, expiresAt: Long = now + CONFIRM_TTL, frames: String = "[\"f-$id\"]", createdAt: Long = now) =
        CanvasHelperJobEntity(
            jobId = id, capability = "py_exec", state = "QUEUED", frameIdsJson = frames, createdAt = createdAt, updatedAt = createdAt,
            jobType = "py_exec", sessionGen = sessionGen, bundleHash = "hash-$id", payloadJson = payload, confirmExpiresAtMs = expiresAt, itemCount = 2, sourceChars = 30,
        )

    fun binding(j: CanvasHelperJobEntity = job("j1")) = ConfirmBinding(j.jobId, j.bundleHash!!, j.sessionGen, j.confirmExpiresAtMs!!)

    suspend fun insert(id: String, sessionGen: Long = 7, expiresAt: Long = now + CONFIRM_TTL, createdAt: Long = now): CanvasHelperJobEntity {
        val j = job(id, sessionGen = sessionGen, expiresAt = expiresAt, createdAt = createdAt)
        check(dao.insertHelperJobFenced(j, fence(), live)) { "fixture insert refused" }
        return j
    }

    suspend fun insertConfirmed(id: String, sessionGen: Long = 7, createdAt: Long = now): CanvasHelperJobEntity {
        val j = insert(id, sessionGen, createdAt = createdAt)
        check(dao.confirmJobFenced(id, binding(j), live, now) == com.rokid.context.memory.canvas.HelperConfirmOutcome.CONFIRMED) { "fixture confirm refused" }
        return j
    }

    /** Insert, confirm and lease [id] to [helper]; returns the leased job. */
    suspend fun leased(id: String, helper: String = "h1", sessionGen: Long = 7): com.rokid.context.memory.canvas.LeasedHelperJob {
        insertConfirmed(id, sessionGen)
        return checkNotNull(dao.leaseHelperJob(listOf("py_exec"), helper, sessionGen, now, LEASE_MS, JOB_MS, QUEUE_WAIT, live)) { "fixture lease refused" }
    }

    fun close() = db.close()

    companion object {
        const val CONFIRM_TTL = 120_000L
        const val LEASE_MS = 10_000L
        const val JOB_MS = 130_000L
        const val QUEUE_WAIT = 30_000L
    }
}
