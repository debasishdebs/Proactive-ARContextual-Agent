package com.rokid.context.canvas.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.canvas.moment.MomentCadenceWorker
import com.rokid.context.canvas.moment.MomentTriggers
import com.rokid.context.canvas.moment.SceneTriggers
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CleanupOutboxEntity
import com.rokid.context.memory.db.CleanupPayload
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
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

/** The production hook: the existing 15-minute cadence worker drains the outbox (no new service), with the app's real file port and the not-yet-reachable glasses and helper. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OutboxWiringTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); MomentTriggers.resetForTests(); SceneTriggers.resetForTests(); OutboxTriggers.resetForTests()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null); MomentTriggers.resetForTests(); SceneTriggers.resetForTests(); OutboxTriggers.resetForTests() }

    private fun queue(target: String, json: String) = runBlocking { db.momentDao().insertOutbox(listOf(CleanupOutboxEntity(target = target, payloadJson = json, createdAt = 1))) }

    @Test fun theCadenceWorkerDeletesAQueuedFrameFileAndItsRowAndKeepsUnreachableRows() = runBlocking<Unit> {
        val jpg = File(RokidFrameInbox.inboxDir(ctx), "f1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val keep = File(RokidFrameInbox.inboxDir(ctx), "keep.jpg").apply { writeBytes(byteArrayOf(1)) }
        queue(CleanupTarget.PHONE_FILE, CleanupPayload.one("path", jpg.absolutePath)); queue(CleanupTarget.GLASSES_RANGE, CleanupPayload.range(1, 2))
        assertEquals(androidx.work.ListenableWorker.Result.success(), TestListenableWorkerBuilder<MomentCadenceWorker>(ctx).build().doWork())
        assertFalse(jpg.exists()); assertTrue(keep.exists())
        assertEquals(listOf(CleanupTarget.GLASSES_RANGE), db.momentDao().pendingOutbox().map { it.target })
    }

    @Test fun aFileOutsideTheAppsFrameFoldersIsNeverDeletedByARow() = runBlocking<Unit> {
        val other = File(ctx.filesDir, "other/secret.txt").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        queue(CleanupTarget.PHONE_FILE, CleanupPayload.one("path", other.absolutePath))
        TestListenableWorkerBuilder<MomentCadenceWorker>(ctx).build().doWork()
        assertTrue(other.exists()); assertEquals(1, db.momentDao().outboxCount())
    }
}
