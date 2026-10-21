package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.model.ModelImageInput
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fix round 1 (I2): the prompt editor's test bed follows the same photo gate and floor as the production ask. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TestBedPhotosTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun answers(allowed: Boolean, decrypts: MutableList<String>): AnswerService {
        val ai = AiGraph.get(ctx)
        return AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() }, photosAllowed = { allowed },
            photos = { _, _ -> listOf(PhotoHit("f1", PhotoSource.IMAGE, 0.5, 1_000, 2_000, true, false, null, null, null)) },
            photoImages = { ids -> decrypts += ids; ids.associateWith { ModelImageInput("image/jpeg", "AAA") } })
    }

    private suspend fun seed() {
        val db = MemoryGraph.get(ctx).db
        db.frameDao().insertIgnore(FrameEntity("f1", 1_000, "/f1.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity("f1", null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
    }

    @Test fun switchOffDecryptsNothingAndWritesNoImageLines() = runBlocking {
        seed(); val decrypts = mutableListOf<String>()
        val p = TestBedInputs(MemoryGraph.get(ctx), answers(false, decrypts)).prepare(PromptKey.ANSWER, TestInput.Question("where are my keys"))
        assertEquals(emptyList<String>(), decrypts); assertTrue(p.images.isEmpty())
        assertTrue(p.vars.retrievedMemories, "image=" !in p.vars.retrievedMemories && "photo \u00b7" !in p.vars.retrievedMemories)
    }

    @Test fun switchOnReturnsTheImagesTheLinesPointAt() = runBlocking {
        seed(); val decrypts = mutableListOf<String>()
        val p = TestBedInputs(MemoryGraph.get(ctx), answers(true, decrypts)).prepare(PromptKey.ANSWER, TestInput.Question("where are my keys"))
        assertEquals(1, p.images.size); assertTrue(p.vars.retrievedMemories, "image=1" in p.vars.retrievedMemories)
    }
}
