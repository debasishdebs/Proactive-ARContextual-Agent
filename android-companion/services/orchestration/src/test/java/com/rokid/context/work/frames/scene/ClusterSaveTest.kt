package com.rokid.context.work.frames.scene

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.ClusterOutput
import com.rokid.context.ai.contract.ClusterResult
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave 2 §P4.1/§P2.5: the cluster commit is one transaction that starts with the CAS on the cluster row. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClusterSaveTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val claim = "k1"

    // c1: members f1..f4, representatives f1 (medoid) and f3.  c2: members g1, g2, representative g1.
    private val sent = mapOf("c1" to listOf("f1", "f3"), "c2" to listOf("g1"))

    @Before fun setUp() = runBlocking<Unit> {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        seed("c1", listOf("f1", "f2", "f3", "f4"), listOf("f1", "f3"))
        seed("c2", listOf("g1", "g2"), listOf("g1"))
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(cid: String, members: List<String>, reps: List<String>, claimId: String = claim) {
        db.frameClusterDao().upsert(
            FrameClusterEntity(cid, 1, 2, "gap", reps.joinToString(",", "[", "]") { "\"$it\"" }, null, "SUMMARIZING", 1, claimId, 5, null, 5, "STILL", members.size, 2),
        )
        members.forEachIndexed { i, f ->
            db.frameDao().insertIgnore(FrameEntity(f, 100L + i, "/x/$f.jpg", "glasses"))
            db.frameMetaDao().upsert(FrameMetaEntity(f, null, null, "STILL", false, null, null, null, null, 0.1f, null, cid, null, null, 100L + i))
            db.frameStateDao().insertIgnore(listOf(FrameStateEntity(f, "IN_CLUSTER", 0, null, null, null, 0)))
        }
    }

    private fun fact(text: String, vararg ids: String) = Fact(text, "object", 0.8, listOf("Kitchen"), ids.toList())
    private fun output(
        c1: ClusterResult = ClusterResult("c1", "A kitchen", listOf(fact("Red mug", "f1", "f3"), fact("Kettle", "f3"))),
        c2: ClusterResult = ClusterResult("c2", "A hallway", listOf(fact("Coat", "g1"))),
        captions: List<String> = listOf("f1", "f3", "g1"),
        delivered: Boolean = true,
    ) = ClusterOutput(listOf(c1, c2), captions.map { FrameCaption(it, "Caption $it") }, imagesDelivered = delivered)

    private suspend fun save(out: ClusterOutput = output(), generation: Long = MemoryWipe.generation.get(), sentMap: Map<String, List<String>> = sent, claimId: String = claim) =
        ClusterSave.save(ctx, claimId, out, sentMap, generation, 1_000, ReviewState.OK)

    private suspend fun forget(id: String) {
        db.forgetDao().insertTombstone(ForgetTombstoneEntity(id, 1))
        db.frameDao().deleteByIds(listOf(id))
    }

    private suspend fun memoryIds() = MemoryGraph.get(ctx).memories.all().map { it.id }.toSet()

    // codex r10: a scene summary or caption whose photos were never proven delivered to the model is never a memory
    @Test fun anOutputWithoutDeliveryProofIsNeverSaved() = runBlocking<Unit> {
        assertEquals(ClusterSaveResult.Undelivered, save(output(delivered = false)))
        assertTrue(memoryIds().isEmpty())
        assertTrue(db.frameSourceDao().let { d -> listOf("f1", "f3", "g1").all { d.memoriesForFrame(it).isEmpty() } })
        assertEquals("SUMMARIZING", db.frameClusterDao().get("c1")?.state) // untouched: the pipeline retries it with backoff
    }

    @Test fun saveWritesSummaryFactsCaptionsAndProvenance() = runBlocking<Unit> {
        val r = save() as ClusterSaveResult.Saved
        assertEquals(setOf("rokid_cl_k1_0", "rokid_cl_k1_0f0", "rokid_cl_k1_0f1", "rokid_cl_k1_1", "rokid_cl_k1_1f0", "rokid_cl_k1_c0", "rokid_cl_k1_c1", "rokid_cl_k1_c2"), memoryIds())
        assertEquals(memoryIds(), r.memories.map { it.memoryId }.toSet())
        val m = MemoryGraph.get(ctx).memories.all().associateBy { it.id }
        val s = m.getValue("rokid_cl_k1_0")
        assertEquals("cluster_summary", s.type); assertEquals(MemoryTier.LONG, s.tier); assertEquals(ReviewState.OK, s.reviewState); assertEquals("f1", s.frameId)
        assertEquals("A kitchen", s.text)
        val f = m.getValue("rokid_cl_k1_0f0")
        assertEquals("object", f.type); assertEquals(MemoryTier.SHORT, f.tier); assertEquals("kitchen", f.tags)
        assertEquals("caption", m.getValue("rokid_cl_k1_c0").type); assertEquals(MemoryTier.LONG, m.getValue("rokid_cl_k1_c0").tier)
        assertEquals("rokid_cl_k1_0", db.frameClusterDao().get("c1")!!.memoryId); assertEquals("SUMMARIZED", db.frameClusterDao().get("c1")!!.state)
        assertEquals("rokid_cl_k1_1", db.frameClusterDao().get("c2")!!.memoryId)
        val src = db.frameSourceDao()
        val sum = src.forMemory("rokid_cl_k1_0").associate { it.frameId to it.role }
        assertEquals(mapOf("f1" to "CLUSTER_REP", "f3" to "CLUSTER_REP", "f2" to "CLUSTER_MEMBER", "f4" to "CLUSTER_MEMBER"), sum)
        assertTrue(src.forMemory("rokid_cl_k1_0").all { it.clusterId == "c1" })
        assertEquals(setOf("f1", "f3"), src.forMemory("rokid_cl_k1_0f0").map { it.frameId }.toSet())
        assertTrue(src.forMemory("rokid_cl_k1_0f0").all { it.role == "FACT" && it.clusterId == "c1" })
        assertEquals(listOf("f1"), src.forMemory("rokid_cl_k1_c0").map { it.frameId }); assertEquals("FACT", src.forMemory("rokid_cl_k1_c0").single().role)
        assertEquals(mapOf("g1" to "CLUSTER_REP", "g2" to "CLUSTER_MEMBER"), src.forMemory("rokid_cl_k1_1").associate { it.frameId to it.role })
    }

    @Test fun casFailureWritesNothing() = runBlocking<Unit> {
        // c2's claim was retaken by another worker: the whole call is dropped, c1 included.
        db.frameClusterDao().upsert(db.frameClusterDao().get("c2")!!.copy(claimId = "other"))
        assertEquals(ClusterSaveResult.Stale, save())
        assertTrue(memoryIds().isEmpty())
        assertEquals("SUMMARIZING", db.frameClusterDao().get("c1")!!.state); assertNull(db.frameClusterDao().get("c1")!!.memoryId)
        assertEquals("IN_CLUSTER", db.frameStateDao().get("f2")!!.state)
        assertTrue(db.frameSourceDao().forMemory("rokid_cl_k1_0").isEmpty())
    }

    @Test fun wipeDuringCallWritesNothing() = runBlocking<Unit> {
        val before = MemoryWipe.generation.get()
        MemoryWipe.generation.incrementAndGet()
        try {
            assertEquals(ClusterSaveResult.Wiped, save(generation = before))
            assertTrue(memoryIds().isEmpty())
            assertEquals("SUMMARIZING", db.frameClusterDao().get("c1")!!.state)
        } finally { MemoryWipe.generation.set(before) }
    }

    @Test fun forgottenRepDropsSave() = runBlocking<Unit> {
        // Every representative of c1 was forgotten meanwhile: c1 fails cleanly and no memory points at a deleted frame; c2 still saves.
        forget("f1"); forget("f3")
        val r = save() as ClusterSaveResult.Saved
        assertEquals(listOf("c1"), r.failedClusters)
        assertTrue(memoryIds().none { it.startsWith("rokid_cl_k1_0") })
        assertTrue(memoryIds().containsAll(setOf("rokid_cl_k1_1", "rokid_cl_k1_1f0")))
        assertEquals("FAILED_FINAL", db.frameClusterDao().get("c1")!!.state); assertNull(db.frameClusterDao().get("c1")!!.memoryId)
        assertEquals("SUMMARIZED", db.frameClusterDao().get("c2")!!.state)
        assertTrue(db.frameSourceDao().forMemory("rokid_cl_k1_0").isEmpty())
        // Its surviving members are skipped photos, never described one by one.
        assertEquals("NEAR_DUP", db.frameStateDao().get("f2")!!.state)
        assertEquals("cluster_failed", db.frameStateDao().get("f2")!!.lastError)
    }

    @Test fun oneForgottenRepIsDroppedAndTheSaveKeepsTheRest() = runBlocking<Unit> {
        // Only f1 forgotten: the medoid becomes f3 and the fact citing f1 loses that source only.
        forget("f1")
        val r = save(output(captions = listOf("f3", "g1"))) as ClusterSaveResult.Saved
        assertTrue(r.failedClusters.isEmpty())
        assertEquals("f3", MemoryGraph.get(ctx).memories.all().first { it.id == "rokid_cl_k1_0" }.frameId)
        assertEquals(listOf("f3"), db.frameSourceDao().forMemory("rokid_cl_k1_0f0").map { it.frameId })
        assertTrue(db.frameSourceDao().forMemory("rokid_cl_k1_0").none { it.frameId == "f1" })
    }

    @Test fun forgottenMemberSkippedInProvenance() = runBlocking<Unit> {
        forget("f2")
        assertTrue(save() is ClusterSaveResult.Saved)
        assertEquals(setOf("f1", "f3", "f4"), db.frameSourceDao().forMemory("rokid_cl_k1_0").map { it.frameId }.toSet())
        assertTrue(db.frameSourceDao().forMemory("rokid_cl_k1_0").none { it.frameId == "f2" })
    }

    @Test fun membersBecomeClustered() = runBlocking<Unit> {
        save()
        assertEquals(listOf("CLUSTERED"), db.frameStateDao().byIds(listOf("f1", "f2", "f3", "f4", "g1", "g2")).map { it.state }.distinct())
    }

    @Test fun askClaimedMemberNotOverwritten() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET state='EXTRACTING', claimId='ask', claimedAt=7 WHERE frameId='f2'")
        save()
        val f2 = db.frameStateDao().get("f2")!!
        assertEquals("EXTRACTING", f2.state); assertEquals("ask", f2.claimId)
        assertEquals("CLUSTERED", db.frameStateDao().get("f4")!!.state)
    }

    @Test fun idsStartWithRokidPrefix() = runBlocking<Unit> {
        save()
        assertTrue(memoryIds().all { it.startsWith("rokid_cl_k1_") && !it.startsWith("rokid_review_") })
    }

    @Test fun unsureFactsAreSavedNeedsReviewWhileSummaryAndCaptionsStayOk() = runBlocking<Unit> {
        ClusterSave.save(ctx, claim, output(), sent, MemoryWipe.generation.get(), 1_000, ReviewState.NEEDS_REVIEW)
        val m = MemoryGraph.get(ctx).memories.all().associateBy { it.id }
        assertEquals(ReviewState.NEEDS_REVIEW, m.getValue("rokid_cl_k1_0f0").reviewState)
        assertEquals(ReviewState.OK, m.getValue("rokid_cl_k1_0").reviewState)
        assertEquals(ReviewState.OK, m.getValue("rokid_cl_k1_c0").reviewState)
    }
}
