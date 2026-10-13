package com.rokid.context.work

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
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        settings = SourceSettingsStore(ctx.getSharedPreferences("frame_ingest_sources", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        val location = LocationSource({ Fix(1.0, 2.0, System.currentTimeMillis()) }, { _, _ -> lookups++; Place("Indiranagar", "Bengaluru", null) }, Dispatchers.Unconfined)
        SourcesGraph.resetForTests(SourcesRuntime({ settings }, listOf(location), granted = { true }))
    }

    @After fun tearDown() { SourcesGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

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
}
