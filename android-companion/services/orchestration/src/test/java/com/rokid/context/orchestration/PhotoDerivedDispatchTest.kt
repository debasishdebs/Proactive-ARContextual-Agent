package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.Fact
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.GonePhotos
import com.rokid.context.model.PhotoLiveness
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex r11 P1-2: a call whose text is derived from a photo (facts + frame metadata) passes the router's guard like a photo upload. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoDerivedDispatchTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val sent = mutableListOf<String>()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private val facts = listOf(Fact("A red mug", "object", 0.4, emptyList(), listOf("f1")))
    private fun liveness(gone: Set<String>, checks: MutableList<String>) = object : PhotoLiveness {
        override suspend fun <T> holdUploadSlot(block: suspend () -> T): T { checks += "slot"; return block() }
        override suspend fun gone(ids: Set<String>): GonePhotos { checks += "check:${ids.sorted()}"; return GonePhotos(ids intersect gone) }
        override suspend fun goneMemories(ids: Set<String>): Set<String> { checks += "mem:${ids.sorted()}"; return ids intersect gone }
        override suspend fun goneMarks(ids: Set<String>): Set<String> = emptySet()
    }

    @Test fun theCriticOverAForgottenPhotosFactsIsNeverSent() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p -> sent += p; """{"verdict":"pass","reasons":[]}""" }
        TestAi.bindAll(ai)
        val checks = mutableListOf<String>()
        val pass = ExtractFollowUp.modelCriticPasses(ai, facts, listOf("id=f1 · ts=1 · source=rokid_glasses"), setOf("f1"), liveness(setOf("f1"), checks), CallClass.EXTRACT)
        assertFalse("the local verdict stands", pass)
        assertTrue("nothing was sent: $sent", sent.isEmpty())
        assertEquals(listOf("slot", "check:[f1]"), checks)
    }

    @Test fun theCriticOverALivePhotoIsRecheckedThenSent() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p -> sent += p; """{"verdict":"pass","reasons":[]}""" }
        TestAi.bindAll(ai)
        val checks = mutableListOf<String>()
        assertTrue(ExtractFollowUp.modelCriticPasses(ai, facts, listOf("id=f1"), setOf("f1"), liveness(emptySet(), checks), CallClass.EXTRACT))
        assertEquals(1, sent.size); assertEquals(listOf("slot", "check:[f1]"), checks)
    }

    // codex r12: memory liveness is the Forget rule, not "has a JPEG": an expired photo's memory stays live, an INVALIDATED one or a forgotten photo's cite does not
    @Test fun frameLivenessOfSourceMemories() = runBlocking<Unit> {
        val db = MemoryGraph.get(ctx).db
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("fx", 1, "/fx.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity("fx", null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
        fun mem(id: String, review: com.rokid.context.memory.db.ReviewState) = com.rokid.context.memory.db.MemoryEntity(id, "fx", "t", "note", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8, review, false, 1, false, "t")
        db.memoryDao().insert(mem("ok", com.rokid.context.memory.db.ReviewState.OK)); db.memoryDao().insert(mem("inv", com.rokid.context.memory.db.ReviewState.INVALIDATED))
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("fx", 1)); db.frameDao().deleteByIds(listOf("fx")) // the photo expired: row gone, metadata kept
        val l = com.rokid.context.work.frames.FrameLiveness.forGraph(MemoryGraph.get(ctx))
        assertEquals(setOf("inv", "missing", "frame:gone"), l.goneMemories(setOf("ok", "inv", "missing", "frame:fx", "frame:gone")))
        assertEquals("an expired photo itself is not live for an upload", setOf("fx"), l.gone(setOf("fx")).ids)
    }

    @Test fun photoDerivedTextWithoutALivenessIsRefused() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p -> sent += p; """{"verdict":"pass","reasons":[]}""" }
        TestAi.bindAll(ai)
        assertFalse(ExtractFollowUp.modelCriticPasses(ai, facts, listOf("id=f1"), setOf("f1"), null, CallClass.EXTRACT))
        assertTrue(sent.isEmpty())
    }
}
