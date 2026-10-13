package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.legacy.LegacyImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryWipeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun deleteAllRemovesMemoryButKeepsSettingsAndSearchStillWorks() = runBlocking {
        val g = MemoryGraph.get(ctx)
        val ai = TestAi.graph(ctx) { "{}" }; TestAi.bindAll(ai); ai.prompts.ensureSeeded()
        val jpg = File(ctx.filesDir, "rokid_archive/f1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        g.frames.add(FrameEntity("f1", 1, jpg.absolutePath, "rokid_glasses"))
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "Milk carton"))
        g.asks.add(AskEntity("a1", 1, "q", "a", "[\"m1\"]", 1, "phone_typed", false))
        g.traces.upsert(SummaryTraceEntity("s1", "TRUSTED", 1, "ok"))
        val s = g.spaces.startBuilding("fp", "{}", 2, "[]", 1); g.spaces.put("m1", s.id, floatArrayOf(1f, 0f))

        assertEquals(1, MemoryWipe.deleteAll(ctx))
        assertFalse(jpg.exists())
        assertEquals(0, g.db.memoryDao().count()); assertEquals(0, g.db.frameDao().count())
        assertTrue(g.asks.recent(10).first().isEmpty()); assertTrue(g.traces.recent(10).isEmpty())
        assertTrue(g.db.embeddingDao().vectors(s.id).isEmpty())
        assertEquals("Azure", ai.endpoints.byId("az")!!.name) // settings survive
        assertEquals(1, ai.prompts.active(PromptKey.ANSWER).version)
        g.memories.add(LegacyImporter.toMemory("m2", "short_term", 0.9, 2, "Keys on the shelf"))
        assertEquals(listOf("m2"), g.memories.search("keys", 5).map { it.id }) // FTS rebuilt after the vacuum
    }

    /** Task 8 carry + F21: no vector or space survives, and a JPEG still waiting in a frame folder (no row yet) goes too. */
    @Test fun deleteAllLeavesNoVectorsSpacesOrUnlistedFrameFiles() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "Milk carton"))
        val active = g.spaces.startBuilding("fp1", "{}", 2, "[]", 1); g.spaces.put("m1", active.id, floatArrayOf(1f, 0f)); assertTrue(g.spaces.swapIfComplete(active.id))
        val other = g.spaces.startBuilding("fp2", "{}", 2, "[]", 2); g.spaces.put("m1", other.id, floatArrayOf(0f, 1f))
        val waiting = File(ctx.filesDir, "rokid_inbox/w.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        val unrelated = File(ctx.filesDir, "keep.bin").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(1, MemoryWipe.deleteAll(ctx))
        assertFalse(waiting.exists()); assertTrue(unrelated.exists())
        assertTrue(g.db.embeddingDao().observeSpaces().first().isEmpty())
        assertTrue(g.db.embeddingDao().vectors(active.id).isEmpty() && g.db.embeddingDao().vectors(other.id).isEmpty())
        assertEquals(null, g.db.embeddingDao().current())
        unrelated.delete()
    }
}
