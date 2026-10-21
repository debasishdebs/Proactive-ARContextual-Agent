package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.ClusterOutput
import com.rokid.context.ai.contract.ClusterResult
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.CallClass
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.work.frames.scene.ClusterSave
import com.rokid.context.work.frames.scene.ClusterSaveResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave 2 Task 6: the cluster call of the pipeline (claims on frame_cluster, the 60 s wait, settle and recovery). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClusterCallTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private var now = 1_793_000_000_000L
    private class Call(val clusters: List<String>, val reps: Map<String, List<String>>, val claim: String, val cls: CallClass)
    private val calls = mutableListOf<Call>()
    private class Crash : Error()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    /** A CLOSED cluster of [members] (frames f<c>_1..), representatives the first [reps] of them, closed [closedAgoMs] ago. */
    private suspend fun cluster(id: String, members: Int = 5, reps: Int = 3, closedAgoMs: Long = 120_000L, state: String = "CLOSED", attempts: Int = 0, memberState: String = "IN_CLUSTER", frameTs: Long = now - 600_000L) {
        val ids = (1..members).map { "${id}_$it" }
        val repJson = ids.take(reps).joinToString(",", "[", "]") { "\"$it\"" }
        val closedAt = now - closedAgoMs
        db.frameClusterDao().upsert(FrameClusterEntity(id, closedAt - 60_000, closedAt, "gap", repJson, null, state, attempts, null, null, null, closedAt, "STILL", members, closedAt))
        ids.forEachIndexed { i, f ->
            db.frameDao().insertIgnore(FrameEntity(f, frameTs + i, "/x/$f.jpg", "rokid_glasses"))
            db.frameMetaDao().upsert(FrameMetaEntity(f, null, null, "STILL", false, null, null, null, null, 0.1f, null, id, null, null, frameTs + i, routedAt = frameTs + i))
            db.frameStateDao().insertIgnore(listOf(FrameStateEntity(f, memberState, 0, null, null, null, frameTs + i)))
        }
    }

    private suspend fun st(id: String) = db.frameStateDao().get(id)!!
    private suspend fun cl(id: String) = db.frameClusterDao().get(id)!!

    /** Settles like ClusterSave's CAS does, without writing memories. */
    private fun completing(extra: suspend (List<FrameClusterEntity>) -> ClusterOutcome? = { null }): suspend (List<FrameClusterEntity>, Map<String, List<String>>, String, CallClass) -> ClusterOutcome = { cs, reps, claim, cls ->
        calls += Call(cs.map { it.clusterId }, reps, claim, cls)
        extra(cs) ?: run {
            cs.forEachIndexed { i, c ->
                check(db.frameClusterDao().completeIfClaimed(c.clusterId, claim, "rokid_cl_${claim}_$i", now) == 1)
                db.frameStateDao().settleCluster(c.clusterId, "CLUSTERED", null, now)
            }
            ClusterOutcome.Saved(emptyList())
        }
    }

    private fun pipeline(
        clusterExtractor: suspend (List<FrameClusterEntity>, Map<String, List<String>>, String, CallClass) -> ClusterOutcome = completing(),
        onExtracted: suspend (List<KeyframeCandidate>, List<com.rokid.context.orchestration.SavedMemory>) -> Unit = { _, _ -> },
        releaseAtStart: Boolean = false, claim: () -> String = { "claim-${calls.size}" },
    ) = KeyframePipeline(
        db, { _, _, _ -> error("no per-photo call expected") }, { now }, null, onExtracted = onExtracted, newClaimId = claim, pollMs = 50,
        releaseAtStart = releaseAtStart, processStartMs = { now + 1 }, clusterExtractor = clusterExtractor,
    )

    @Test fun threeClustersOneCall() = runBlocking {
        cluster("c1"); cluster("c2"); cluster("c3")
        assertEquals(3, pipeline().drainOnce())
        val c = calls.single()
        assertEquals(setOf("c1", "c2", "c3"), c.clusters.toSet())
        assertEquals(CallClass.EXTRACT, c.cls)
        assertEquals("two representatives each, the medoid first", mapOf("c1" to listOf("c1_1", "c1_2"), "c2" to listOf("c2_1", "c2_2"), "c3" to listOf("c3_1", "c3_2")), c.reps)
        listOf("c1", "c2", "c3").forEach { assertEquals("SUMMARIZED", cl(it).state) }
        assertEquals("CLUSTERED", st("c1_4").state)
    }

    @Test fun singleClusterWaitsSixtySeconds() = runBlocking {
        cluster("c1", closedAgoMs = 10_000)
        val p = pipeline()
        assertEquals(0, p.drainOnce())
        assertTrue(calls.isEmpty())
        now += 49_000
        assertEquals(0, p.drainOnce()); assertTrue("59 s is not enough", calls.isEmpty())
        now += 1_000
        assertEquals(1, p.drainOnce())
        assertEquals(listOf("c1_1", "c1_2", "c1_3"), calls.single().reps.getValue("c1")) // alone: three photos
    }

    @Test fun sixPhotoCap() = runBlocking {
        cluster("c1", reps = 3); cluster("c2", reps = 3)
        pipeline().drainOnce()
        assertEquals(6, calls.single().reps.values.sumOf { it.size })
        calls.clear()
        cluster("d1"); cluster("d2"); cluster("d3")
        pipeline().drainOnce()
        assertTrue(calls.single().reps.values.sumOf { it.size } <= 6)
    }

    @Test fun staleReleasesAll() = runBlocking {
        cluster("c1"); cluster("c2"); cluster("c3")
        assertEquals(0, pipeline(completing { ClusterOutcome.Stale }).drainOnce())
        listOf("c1", "c2", "c3").forEach { assertEquals("CLOSED", cl(it).state); assertEquals(0, cl(it).attempts); assertNull(cl(it).claimId) }
        assertEquals("IN_CLUSTER", st("c1_1").state)
    }

    @Test fun finalFailureSplitsRepsAndMembers() = runBlocking {
        cluster("c1", attempts = 4)
        assertEquals(0, pipeline(completing { ClusterOutcome.Failed("boom") }).drainOnce())
        assertEquals("FAILED_FINAL", cl("c1").state)
        listOf("c1_1", "c1_2", "c1_3").forEach { assertEquals("FAILED_FINAL", st(it).state) }
        listOf("c1_4", "c1_5").forEach { assertEquals("NEAR_DUP", st(it).state); assertEquals("cluster_failed", st(it).lastError) }
    }

    @Test fun nonFinalFailureKeepsMembersAndBacksOff() = runBlocking {
        cluster("c1", attempts = 1)
        pipeline(completing { ClusterOutcome.Failed("boom") }).drainOnce()
        assertEquals("FAILED", cl("c1").state); assertEquals(2, cl("c1").attempts)
        assertEquals("IN_CLUSTER", st("c1_1").state)
        now += 59_000
        assertEquals(0, pipeline().drainOnce()); assertTrue("backoff 60 s after the second failure", calls.size == 1)
        now += 2_000
        assertEquals(1, pipeline().drainOnce())
    }

    @Test fun leftOutClusterFailsWithAnAttempt() = runBlocking {
        cluster("c1"); cluster("c2", attempts = 4)
        pipeline(completing { cs ->
            val ok = cs.first { it.clusterId == "c1" }
            check(db.frameClusterDao().completeIfClaimed("c1", calls.last().claim, "rokid_cl_x_0", now) == 1)
            db.frameStateDao().settleCluster(ok.clusterId, "CLUSTERED", null, now)
            ClusterOutcome.Saved(emptyList(), leftOut = setOf("c2"))
        }).drainOnce()
        assertEquals("SUMMARIZED", cl("c1").state)
        assertEquals("FAILED_FINAL", cl("c2").state); assertEquals(5, cl("c2").attempts)
        assertEquals("FAILED_FINAL", st("c2_1").state); assertEquals("NEAR_DUP", st("c2_5").state)
    }

    @Test fun offlineReleasesWithoutAttempt() = runBlocking {
        cluster("c1"); cluster("c2"); cluster("c3")
        val p = pipeline(completing { ClusterOutcome.Failed("offline", countsAttempt = false) })
        assertEquals(0, p.drainOnce())
        listOf("c1", "c2", "c3").forEach { assertEquals("CLOSED", cl(it).state); assertEquals(0, cl(it).attempts) }
        assertEquals(0, p.drainUntilIdle(200)) // the offline pause holds the loop
        assertEquals(1, calls.size)
    }

    @Test fun noNudgeOfferForClusters() = runBlocking {
        cluster("c1"); cluster("c2"); cluster("c3")
        var offered = 0
        pipeline(onExtracted = { _, _ -> offered++ }).drainOnce()
        assertEquals(1, calls.size); assertEquals(0, offered)
    }

    @Test fun noRepresentativeLeftFailsTheClusterForGood() = runBlocking {
        cluster("c1", memberState = "EXTRACTING") // an ask took every member
        pipeline().drainOnce()
        assertTrue(calls.isEmpty())
        assertEquals("FAILED_FINAL", cl("c1").state)
    }

    @Test fun killMidCallThenRestartSummarisesOnce() = runBlocking {
        cluster("c1", closedAgoMs = 120_000)
        val real: suspend (List<FrameClusterEntity>, Map<String, List<String>>, String, CallClass) -> ClusterOutcome = { cs, reps, claim, cls ->
            calls += Call(cs.map { it.clusterId }, reps, claim, cls)
            if (calls.size == 1) throw Crash() // the process died between the claim and the commit
            val out = ClusterOutput(
                cs.map { ClusterResult(it.clusterId, "A summary", listOf(Fact("A fact", "object", 0.8, listOf("t"), listOf(reps.getValue(it.clusterId).first())))) },
                reps.values.flatten().map { FrameCaption(it, "caption") },
            )
            val r = ClusterSave.save(ctx, claim, out, reps, MemoryWipe.generation.get(), now, ReviewState.OK) as ClusterSaveResult.Saved
            ClusterOutcome.Saved(r.memories)
        }
        val first = pipeline(real, claim = { "k${calls.size + 1}" })
        try { first.drainOnce(); error("expected the crash") } catch (_: Crash) {}
        assertEquals("SUMMARIZING", cl("c1").state)
        // restart: a new process releases the dead process's claim with no attempt, then summarises once
        val second = pipeline(real, releaseAtStart = true, claim = { "k${calls.size + 1}" })
        assertEquals(1, second.drainUntilIdle(500))
        assertEquals(2, calls.size)
        assertEquals("SUMMARIZED", cl("c1").state); assertEquals(0, cl("c1").attempts)
        val mem = MemoryGraph.get(ctx).memories.all()
        assertEquals(1, mem.count { it.type == "cluster_summary" })
        assertTrue(mem.all { it.id.startsWith("rokid_cl_k2_") })
        assertEquals(0, second.drainUntilIdle(200)); assertEquals(2, calls.size)
        assertEquals(1, MemoryGraph.get(ctx).memories.all().count { it.type == "cluster_summary" })
    }

    @Test fun backstopRequeuesAnAbandonedClusterClaim() = runBlocking {
        cluster("c1", state = "SUMMARIZING")
        db.frameClusterDao().upsert(cl("c1").copy(claimId = "dead", claimedAt = now - FrameRecovery.CLAIM_TIMEOUT_MS - 1))
        assertEquals(1, pipeline().recover())
        assertEquals("CLOSED", cl("c1").state); assertEquals(1, cl("c1").attempts)
    }

    @Test fun askExtractsInClusterFrameOfLastMinute() = runBlocking {
        cluster("c1", frameTs = now - 10_000)       // IN_CLUSTER, captured 10 s ago (cluster still OPEN/CLOSED)
        cluster("old", frameTs = now - 600_000)     // too old for an ask
        val photoCalls = mutableListOf<Pair<List<String>, CallClass>>()
        val p = KeyframePipeline(
            db, { ids, claim, cls ->
                photoCalls += ids to cls
                ids.forEach { check(db.frameStateDao().completeIfClaimed(it, claim, "EXTRACTED", now) == 1) }
                ExtractOutcome.Saved(emptyList())
            }, { now }, null, newClaimId = { "ask-1" }, pollMs = 50, clusterExtractor = completing(),
        )
        assertEquals(3, p.extractForAsk())
        assertEquals(CallClass.USER, photoCalls.single().second)
        assertEquals(setOf("c1_5", "c1_4", "c1_3"), photoCalls.single().first.toSet()) // the newest three of the cluster
        assertEquals("EXTRACTED", st("c1_5").state)
        assertEquals("IN_CLUSTER", st("old_5").state)
    }

    @Test fun askSkipsSummarizingCluster() = runBlocking {
        cluster("c1", state = "SUMMARIZING", frameTs = now - 10_000)
        var called = 0
        val p = KeyframePipeline(db, { _, _, _ -> called++; ExtractOutcome.Saved(emptyList()) }, { now }, null, pollMs = 50, clusterExtractor = completing())
        assertEquals(0, p.extractForAsk(waitMs = 50))
        assertEquals(0, called)
        assertEquals("IN_CLUSTER", st("c1_5").state)
    }

    private suspend fun assertSplit(id: String) {
        assertEquals("FAILED_FINAL", cl(id).state)
        listOf("${id}_1", "${id}_2", "${id}_3").forEach { assertEquals("FAILED_FINAL", st(it).state) }
        listOf("${id}_4", "${id}_5").forEach { assertEquals("NEAR_DUP", st(it).state); assertEquals("cluster_failed", st(it).lastError) }
    }

    private suspend fun abandonedFifth(id: String) {
        cluster(id, state = "SUMMARIZING", attempts = 4)
        db.frameClusterDao().upsert(cl(id).copy(claimId = "dead", claimedAt = now - FrameRecovery.CLAIM_TIMEOUT_MS - 1))
    }

    @Test fun abandonedFifthClaimSplitsAtClaimTime() = runBlocking {
        abandonedFifth("c1")
        pipeline().drainOnce()
        assertTrue(calls.isEmpty())
        assertSplit("c1")
    }

    @Test fun abandonedFifthClaimSplitsInTheBackstop() = runBlocking {
        abandonedFifth("c1")
        assertEquals(1, pipeline().recover())
        assertSplit("c1")
    }

    @Test fun strandedFinalClusterIsSplitAtProcessStart() = runBlocking {
        cluster("c1", state = "FAILED_FINAL", attempts = 5)
        pipeline(releaseAtStart = true).drainUntilIdle(200)
        assertSplit("c1")
    }
}
