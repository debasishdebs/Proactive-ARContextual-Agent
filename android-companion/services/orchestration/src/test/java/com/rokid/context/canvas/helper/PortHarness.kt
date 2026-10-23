package com.rokid.context.canvas.helper

import androidx.test.core.app.ApplicationProvider
import com.rokid.context.memory.canvas.HelperFence
import com.rokid.context.memory.canvas.HelperFenceLive
import com.rokid.context.memory.canvas.HelperJobState
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import kotlinx.serialization.json.JsonObject

/**
 * What the shared contract test needs from "a job port plus the helper side": the in-memory fake (WP1) and the real [HelperJobService] over an in-memory Room database
 * (WP3 DAO) both sit behind it, so the SAME cases run against both. The clock is owned by the harness ([now]).
 */
interface PortHarness {
    val port: HelperJobPort
    var now: Long
    suspend fun request(n: Int = 2): PyRequest
    suspend fun leaseNext(): String?
    suspend fun complete(jobId: String, result: JsonObject): List<String>
    suspend fun fail(jobId: String, code: String): Boolean
    suspend fun cancel(jobId: String): Boolean
    suspend fun voidAll()
    /** A frame Forget reduced to what the fence sees. */
    suspend fun forget()
    suspend fun tick()
    /** Moves the clock; a real helper heartbeats every second while it holds a lease (the fake has no lease timer). */
    suspend fun advance(ms: Long)
    suspend fun state(jobId: String): HelperJobState?
    fun close() {}
}

internal class FakePortHarness : PortHarness {
    override var now = 1_000L
    private var rev = 5L
    private val fake = InMemoryHelperJobPort(now = { now }, sessionGen = { 7 }, newId = Counter()::next, fenceNow = { fenceAt(rev) })
    private class Counter { private var n = 0; fun next() = "job-${++n}" }
    override val port: HelperJobPort get() = fake
    override suspend fun request(n: Int) = fakeRequest(fenceAt(rev), n)
    override suspend fun leaseNext() = fake.leaseNext()
    override suspend fun complete(jobId: String, result: JsonObject) = fake.complete(jobId, result)
    override suspend fun fail(jobId: String, code: String) = fake.fail(jobId, code)
    override suspend fun cancel(jobId: String) = fake.cancel(jobId)
    override suspend fun voidAll() = fake.voidAll()
    override suspend fun forget() { rev++ }
    override suspend fun tick() = fake.tick()
    override suspend fun advance(ms: Long) { now += ms }
    override suspend fun state(jobId: String) = fake.state(jobId)
}

internal class RealPortHarness(waiterPollMs: Long = 600_000L, val sessionGen: () -> Long = { 7L }) : PortHarness {
    override var now = 1_000L
    val db: ContextDatabase = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    val dao = db.canvasSlice3Dao()
    var epoch = DeletionEpoch("install-a", 2, 0)
    val store = DaoHelperJobStore(dao, HelperFenceLive({ 3L }, { epoch }))
    val readiness = InMemoryReadinessStore()
    val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private var ids = 0
    val service = HelperJobService(store, { now }, sessionGen, readiness, { _, _, m -> lines += m }, { "hj_${++ids}" }, waiterPollMs)
    private val leases = HashMap<String, Pair<String, Long>>()
    override val port: HelperJobPort get() = service

    override suspend fun request(n: Int) = fakeRequest(HelperFence(3, epoch, dao.frameForgetRev()), n)

    override suspend fun leaseNext(): String? {
        val l = service.lease("h1", listOf("py_exec")) ?: return null
        leases[l.jobId] = "h1" to l.leaseGen
        return l.jobId
    }
    override suspend fun complete(jobId: String, result: JsonObject): List<String> {
        val (h, g) = leases[jobId] ?: return listOf("job is not leased")
        return when (val r = service.commitResult(jobId, h, g, result)) {
            is ResultCommit.Committed -> emptyList()
            is ResultCommit.Denied -> listOf(r.code)
            is ResultCommit.Invalid -> r.reasons
            ResultCommit.TooLarge -> listOf("too_large")
        }
    }
    override suspend fun fail(jobId: String, code: String): Boolean { val (h, g) = leases[jobId] ?: return false; return service.failJob(jobId, h, g, code) }
    override suspend fun cancel(jobId: String) = service.cancel(jobId)
    override suspend fun voidAll() { service.voidAll("test") }
    override suspend fun forget() { dao.bumpFrameForgetRev() }
    override suspend fun tick() = service.sweep()
    override suspend fun advance(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(left, 1_000L)
            now += step; left -= step
            for ((id, hg) in leases.toList()) if (state(id) == HelperJobState.LEASED) service.heartbeat(id, hg.first, hg.second)
        }
    }
    override suspend fun state(jobId: String) = store.job(jobId)?.let { HelperJobState.valueOf(it.state) }
    override fun close() { db.close() }
}
