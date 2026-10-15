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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    private fun ingestor(enqueue: (android.content.Context) -> Unit, commit: suspend (android.content.Context) -> Unit = {}): FrameIngestor {
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        return FrameIngestor(File(tmp.root, "rokid_inbox"), FrameReassembler(), FrameAdmission(ledger, noPauses), ledger) { HubGraph.onFrameStored(app, enqueue, scope, commit) }
    }

    private fun awaitRunning() { val t = System.currentTimeMillis() + 10_000; while (!KeyframeGraph.get(app).pipeline.isRunning() && System.currentTimeMillis() < t) Thread.sleep(20) }

    private fun chunk() = FrameCodec.split("g1", jpeg, capturedAtMs = now - 60_000 + 140).single()

    @Test fun aStoredFrameQueuesTheIngestAndWakesThePipeline() = runTest {
        var queued = 0
        assertFalse(KeyframeGraph.get(app).pipeline.isRunning())
        val r = ingestor({ queued++ }).onChunk(chunk(), ClockOffset(140), now)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        assertEquals(1, queued)
        awaitRunning()
        assertTrue("the signal started the pipeline loop", KeyframeGraph.get(app).pipeline.isRunning())
    }

    @Test fun theSignalStillRunsWhenQueueingFails() = runTest {
        val r = ingestor({ throw IOException("write failed: ENOSPC (No space left on device)") }).onChunk(chunk(), ClockOffset(140), now)
        assertEquals("stored and acked even though queueing failed", FrameAck(frameId = "g1", ok = true), r.ack)
        awaitRunning()
        assertTrue(KeyframeGraph.get(app).pipeline.isRunning())
    }

    @Test fun theSignalStillRunsWhenTheCommitFails() = runTest {
        ingestor({}, commit = { throw IOException("disk") }).onChunk(chunk(), ClockOffset(140), now)
        awaitRunning()
        assertTrue(KeyframeGraph.get(app).pipeline.isRunning())
    }

    /**
     * Final review I1: FrameIngestor -> onStored -> the receipt is committed by the hub itself (frame row, frame_meta, PENDING
     * frame_state), with no WorkManager job (enqueue is a no-op) -> the pipeline is signalled after that.
     */
    @Test fun aStoredFrameIsCommittedByTheHubItselfBeforeThePipelineIsSignalled() = runTest {
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(app, true)
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger2.txt"))
        val inbox = com.rokid.context.data.frames.RokidFrameInbox.inboxDir(app).also { it.deleteRecursively(); it.mkdirs() }
        val ing = FrameIngestor(inbox, FrameReassembler(), FrameAdmission(ledger, noPauses), ledger, archiveDir = com.rokid.context.data.frames.RokidFrameInbox.archiveDir(app).also { it.mkdirs() }) {
            HubGraph.onFrameStored(app, enqueue = {}, scope = scope)
        }
        assertEquals(FrameAck(frameId = "g1", ok = true), ing.onChunk(chunk(), ClockOffset(140), now).ack)
        val db = MemoryGraph.get(app).db
        val deadline = System.currentTimeMillis() + 10_000
        while (db.frameMetaDao().byWireId("g1") == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val meta = db.frameMetaDao().byWireId("g1")!!
        assertEquals(1, db.frameDao().count())
        assertTrue("a frame_state row exists for the committed frame", db.frameStateDao().get(meta.frameId) != null)
        awaitRunning()
        assertTrue(KeyframeGraph.get(app).pipeline.isRunning())
    }
}
