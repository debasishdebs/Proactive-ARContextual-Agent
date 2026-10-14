package com.rokid.context.work

import com.rokid.context.data.frames.DirSync
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.rokid.context.connectors.sources.Fix
import com.rokid.context.connectors.sources.LocationSource
import com.rokid.context.connectors.sources.Place
import com.rokid.context.connectors.sources.SourceKind
import com.rokid.context.connectors.sources.SourceSettingsStore
import com.rokid.context.connectors.sources.SourcesGraph
import com.rokid.context.connectors.sources.SourcesRuntime
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameIngestWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var settings: SourceSettingsStore
    private var lookups = 0

    @Before fun setUp() {
        DirSync.testOverride = DirSync { true } // Robolectric cannot fsync a directory
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx)
        settings = SourceSettingsStore(ctx.getSharedPreferences("frame_ingest_sources", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val location = LocationSource({ Fix(1.0, 2.0, System.currentTimeMillis()) }, { _, _ -> lookups++; Place("Indiranagar", "Bengaluru", null) }, Dispatchers.Unconfined)
        SourcesGraph.resetForTests(SourcesRuntime({ settings }, listOf(location), granted = { true }))
    }

    @After fun tearDown() { DirSync.testOverride = null; SourcesGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun inboxFrame(name: String) = File(RokidFrameInbox.inboxDir(ctx).apply { mkdirs() }, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun run(data: androidx.work.Data = workDataOf()) = runBlocking {
        assertEquals(ListenableWorker.Result.success(), TestListenableWorkerBuilder<FrameIngestWorker>(ctx).setInputData(data).build().doWork())
    }

    @Test fun placeLabelOnlyWhenLocationEnabled() = runBlocking {
        inboxFrame("rokid_1_a.jpg")
        run()
        assertNull(MemoryGraph.get(ctx).frames.recent(5).single().placeLabel)
        assertEquals(0, lookups)

        settings.set(SourceKind.LOCATION, true)
        inboxFrame("rokid_2_b.jpg")
        inboxFrame("rokid_3_c.jpg")
        run()
        val labelled = MemoryGraph.get(ctx).frames.recent(5).filter { it.placeLabel != null }
        assertEquals(2, labelled.size)
        assertEquals("Indiranagar, Bengaluru", labelled.first().placeLabel)
        assertEquals("one lookup per run", 1, lookups)
    }

    @Test fun singlePathKeepsTheGivenSource() = runBlocking {
        val f = File(RokidFrameInbox.archiveDir(ctx).apply { mkdirs() }, "rokid_phone_9.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        run(workDataOf(FrameIngestWorker.KEY_SOURCE_IMAGE_PATH to f.absolutePath, FrameIngestWorker.KEY_SOURCE to "rokid_phone"))
        assertEquals("rokid_phone", MemoryGraph.get(ctx).frames.recent(5).single().source)
    }

    /** Fix round 1 (I1): the summarizer job names exactly this run's frames, so Capture now never re-summarises older ones. */
    @Test fun summarizerJobNamesOnlyTheFramesOfThisRun() = runBlocking {
        val old = File(RokidFrameInbox.archiveDir(ctx).apply { mkdirs() }, "rokid_old_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals("rokid_old_1", RokidFrameInbox.ingestSinglePath(ctx, old.absolutePath))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx)
        val f = File(RokidFrameInbox.archiveDir(ctx), "rokid_phone_9.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        inboxFrame("rokid_5_e.jpg")
        run(workDataOf(FrameIngestWorker.KEY_SOURCE_IMAGE_PATH to f.absolutePath, FrameIngestWorker.KEY_SOURCE to "rokid_phone"))
        val job = com.rokid.context.data.runtime.QueueRuntimeStore.live(ctx, com.rokid.context.domain.queue.QueuePriority.SUMMARIZER).single()
        val payload = com.rokid.context.data.runtime.QueuePayloads.parse(job.payload)
        val ids = com.rokid.context.data.runtime.QueuePayloads.string(payload, "frame_ids_csv").split(",").toSet()
        assertEquals(2, ids.size)
        assertTrue(ids.toString(), "rokid_phone_9" in ids && "rokid_old_1" !in ids)
        assertEquals(2, com.rokid.context.data.runtime.QueuePayloads.int(payload, "imported_frames", 0))
    }
}
