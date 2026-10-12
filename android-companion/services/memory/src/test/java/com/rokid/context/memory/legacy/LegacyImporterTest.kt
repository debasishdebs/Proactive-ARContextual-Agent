package com.rokid.context.memory.legacy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyImporterTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private lateinit var jpg: File

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        graph = MemoryGraph.get(ctx)
        jpg = File(ctx.filesDir, "rokid_archive/1_a.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        File(ctx.filesDir, "memory_records.log").writeText(
            listOf(
                "rokid_mem_1\tshort_term\t0.9\t100\tMilk carton nearly empty",
                "rokid_mem_1\tshort_term\t0.9\t100\tduplicate line in file",
                "rokid_review_2\treview\t0.2\t101\tneeds review",
                "rokid_mem_3\tshort_term\t0.5\t102\tMetadata only frame",
                "other_4\tshort_term\t0.5\t103\tnot from rokid",
                "broken line without tabs",
                "rokid_mem_5\tlong_term\tnot-a-number\tnot-a-time\tbad numbers",
                "rokid_mem_6\tlong_term\t0.7\t104\tKeys on the shelf",
            ).joinToString("\n", postfix = "\n"),
        )
        File(ctx.filesDir, "captured_frames.log").writeText(
            listOf(
                "f1\t200\trokid_glasses\tx\t${jpg.absolutePath}",
                "f2\t201\trokid_glasses\tx\t/missing/file.jpg",
                "f3\t202\tphone\tx\t${jpg.absolutePath}",
                "f4\t203\trokid_glasses\tsynthetic frame\t${jpg.absolutePath}",
                "f5\tbad",
            ).joinToString("\n", postfix = "\n"),
        )
        File(ctx.filesDir, "summary_trace.log").writeText("s1\tTRUSTED\t300\tframes=3\ns2\tQUEUED\t301\tplaceholder\n")
    }

    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    @Test fun importsOnlyKeepableRowsWithMappedFields() = runTest {
        val report = LegacyImporter(ctx, graph).importAll()
        assertEquals(listOf("rokid_mem_1", "rokid_mem_6"), graph.memories.all().map { it.id })
        assertEquals(MemoryTier.LONG, graph.memories.all().last().tier)
        assertEquals(listOf("f1"), graph.frames.all().map { it.id })
        assertEquals(listOf("s1"), graph.traces.recent(10).map { it.summaryId })
        assertEquals(2, report.memories); assertEquals(1, report.frames); assertEquals(1, report.traces)
        assertTrue(LegacyImporter(ctx, graph).isDone())
    }

    @Test fun skipsMalformedLines() = runTest {
        val report = LegacyImporter(ctx, graph).importAll()
        assertTrue("broken + bad-number + short frame lines are counted", report.skipped >= 3)
    }

    @Test fun rerunIsIdempotent() = runTest {
        LegacyImporter(ctx, graph).importAll()
        val second = LegacyImporter(ctx, graph).importAll()
        assertEquals(0, second.memories + second.frames + second.traces)
        assertEquals(2, graph.memories.all().size)
    }

    @Test fun resumesAfterPartialImport() = runTest {
        // Simulate a crash after one memory was written and before the done marker.
        graph.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 100, "Milk carton nearly empty"))
        val report = LegacyImporter(ctx, graph).importAll()
        assertEquals(1, report.memories)
        assertEquals(2, graph.memories.all().size)
    }

    @Test fun reviewTypeMapsToNeedsReview() {
        assertEquals(ReviewState.NEEDS_REVIEW, LegacyImporter.legacyReviewState("review"))
        assertEquals(MemoryTier.MEDIUM, LegacyImporter.legacyTier("medium_term"))
        assertEquals(MemoryTier.SHORT, LegacyImporter.legacyTier("anything_else"))
    }
}
