package com.rokid.context.data.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.orchestration.MemoryWipe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 20 fix round 2: the queue and the Delete-all generation change together, under the queue's lock. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueGenerationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { QueueRuntimeStore.clearAll(ctx); AppConfigStore.setPipelineEnabled(ctx, true) }
    @After fun tearDown() = QueueRuntimeStore.clearAll(ctx)

    private fun write(text: String) = QueuePayloads.explicitWrite(text, "en", manual = true)

    /** N1: a claim is stamped with the generation it was claimed under; clearAll moves the generation past it. */
    @Test fun aClaimBeforeClearAllIsStampedWithTheOldGenerationAndOneAfterWithTheNew() {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, write("before"), personaSnapshot = "p")
        val before = QueueRuntimeStore.claim(ctx)!!
        assertEquals(MemoryWipe.generation.get(), before.generation)
        QueueRuntimeStore.clearAll(ctx)
        assertTrue(MemoryWipe.generation.get() > before.generation)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, write("after"), personaSnapshot = "p")
        val after = QueueRuntimeStore.claim(ctx)!!
        assertEquals(MemoryWipe.generation.get(), after.generation)
        assertEquals("after", QueuePayloads.string(QueuePayloads.parse(after.job.payload), "raw_input"))
    }

    /** N2: a producer that read the generation before its memory read cannot queue that text after a wipe. */
    @Test fun aStaleGenerationEnqueueIsDropped() {
        val read = MemoryWipe.generation.get()
        QueueRuntimeStore.clearAll(ctx) // a Delete all happens between the producer's read and its enqueue
        assertNull(QueueRuntimeStore.enqueueIfCurrent(ctx, read, QueuePriority.PRIORITY_REPROCESS, QueuePayloads.reprocess("r", listOf("m1")), personaSnapshot = "p"))
        assertTrue(QueueRuntimeStore.recent(ctx, 10).isEmpty())
        assertNotNull(QueueRuntimeStore.enqueueIfCurrent(ctx, MemoryWipe.generation.get(), QueuePriority.PRIORITY_REPROCESS, QueuePayloads.reprocess("r", listOf("m2")), personaSnapshot = "p"))
        assertEquals(1, QueueRuntimeStore.recent(ctx, 10).size)
    }
}
