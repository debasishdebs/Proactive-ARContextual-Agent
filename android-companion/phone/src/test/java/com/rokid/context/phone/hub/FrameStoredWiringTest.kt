package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.data.frames.DirSync
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.PauseHistory
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.work.frames.KeyframeGraph
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Review M8: FrameIngestor -> onStored -> the keyframe pipeline is woken (it was only tested with seeded frame_state rows). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class FrameStoredWiringTest {
    @get:Rule val tmp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_793_000_000_000L
    private val jpeg = ByteArray(3_000) { (it % 200).toByte() }
    private val noPauses = object : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = false }

    @Before fun setUp() {
        DirSync.testOverride = DirSync { true }
        MemoryGraph.resetForTests(testDb(app))
        KeyframeGraph.resetForTests()
    }

    @After fun tearDown() {
        KeyframeGraph.resetForTests(); MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null); DirSync.testOverride = null
    }

    private fun ingestor(enqueue: (android.content.Context) -> Unit): FrameIngestor {
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        return FrameIngestor(File(tmp.root, "rokid_inbox"), FrameReassembler(), FrameAdmission(ledger, noPauses), ledger) { HubGraph.onFrameStored(app, enqueue) }
    }

    private fun chunk() = FrameCodec.split("g1", jpeg, capturedAtMs = now - 60_000 + 140).single()

    @Test fun aStoredFrameQueuesTheIngestAndWakesThePipeline() = runTest {
        var queued = 0
        assertFalse(KeyframeGraph.get(app).pipeline.isRunning())
        val r = ingestor { queued++ }.onChunk(chunk(), ClockOffset(140), now)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(1, queued)
        assertTrue("the signal started the pipeline loop", KeyframeGraph.get(app).pipeline.isRunning())
    }

    @Test fun theSignalStillRunsWhenQueueingFails() = runTest {
        val r = ingestor { throw IOException("write failed: ENOSPC (No space left on device)") }.onChunk(chunk(), ClockOffset(140), now)
        assertEquals("stored and acked even though queueing failed", FrameAck(frameId = "g1", ok = true), r.ack)
        assertTrue(KeyframeGraph.get(app).pipeline.isRunning())
    }
}
