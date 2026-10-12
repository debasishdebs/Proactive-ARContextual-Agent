package com.rokid.context.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.frames.CapturedFrame
import com.rokid.context.data.frames.FrameStreamStore
import com.rokid.context.data.runtime.MemoryRecordStore
import com.rokid.context.data.runtime.SummaryTrace
import com.rokid.context.data.runtime.SummaryTraceStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
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
class LegacyFacadesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() = MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun jpg(name: String) = File(ctx.filesDir, name).apply { writeBytes(byteArrayOf(1)) }.absolutePath

    @Test fun framesRoundTripAndRejectNonRokidOrMissingPreview() {
        assertTrue(FrameStreamStore.append(ctx, CapturedFrame("f1", 10, "rokid_glasses", "p", jpg("a.jpg"))))
        assertFalse(FrameStreamStore.append(ctx, CapturedFrame("f2", 11, "phone", "p", jpg("b.jpg"))))
        assertFalse(FrameStreamStore.append(ctx, CapturedFrame("f3", 12, "rokid_glasses", "p", "/missing.jpg")))
        assertEquals(listOf("f1"), FrameStreamStore.recent(ctx, 10).map { it.frameId })
        assertEquals(listOf("f1"), FrameStreamStore.byIds(ctx, listOf("f1", "nope")).map { it.frameId })
    }

    // NB: Robolectric names filesDir after the test method, so the name must not contain "synthetic"/"screenshot" (isRealPhotoFrame checks the path).
    @Test fun purgeNonPhotoDeletesFakeFrameAndItsFile() {
        val synth = jpg("synthetic_1.jpg")
        FrameStreamStore.append(ctx, CapturedFrame("s1", 10, "rokid_glasses", "synthetic", synth))
        FrameStreamStore.append(ctx, CapturedFrame("r1", 11, "rokid_glasses", "real", jpg("r.jpg")))
        val r = FrameStreamStore.purgeNonPhotoAndSynthetic(ctx)
        assertEquals(1, r.kept); assertEquals(1, r.dropped); assertEquals(1, r.deletedFiles)
        assertFalse(File(synth).exists())
    }

    @Test fun tracesUpsertNewestFirst() {
        SummaryTraceStore.upsert(ctx, SummaryTrace("s1", "QUEUED", 1, "a"))
        SummaryTraceStore.upsert(ctx, SummaryTrace("s2", "QUEUED", 2, "b"))
        SummaryTraceStore.upsert(ctx, SummaryTrace("s1", "TRUSTED", 3, "c"))
        assertEquals(listOf("s1" to "TRUSTED", "s2" to "QUEUED"), SummaryTraceStore.recent(ctx, 10).map { it.summaryId to it.state })
    }

    @Test fun facadeTriggersImportOnFirstUse() {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tImported before first use\n")
        assertEquals(listOf("rokid_mem_9"), MemoryRecordStore.recent(ctx, 10).map { it.id })
    }

    @Test fun ensureLegacyImportedRunsImportOnlyOnce() {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tOnce only\n")
        val first = MemoryGraph.ensureLegacyImported(ctx)
        val second = MemoryGraph.ensureLegacyImported(ctx)
        assertEquals(1, first?.memories)
        assertEquals(null, second)
        assertEquals(1, MemoryRecordStore.recent(ctx, 10).size)
    }
}
