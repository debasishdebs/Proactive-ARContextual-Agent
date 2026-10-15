package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.frames.DirSync
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
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
class FrameAdoptionTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_793_000_000_000L
    private val prefs get() = ctx.getSharedPreferences("adoption_test", Context.MODE_PRIVATE)
    private val db get() = MemoryGraph.get(ctx).db
    private val inbox get() = RokidFrameInbox.inboxDir(ctx)
    private val archive get() = RokidFrameInbox.archiveDir(ctx)

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        prefs.edit().clear().commit()
        inbox.deleteRecursively(); archive.deleteRecursively()
        inbox.mkdirs(); archive.mkdirs()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun frame(id: String, withFile: Boolean = true, ts: Long = 10): FrameEntity {
        val f = File(archive, "$id.jpg").apply { if (withFile) writeBytes(byteArrayOf(1, 2)) }
        return FrameEntity(id, ts, f.absolutePath, "rokid_glasses").also { db.frameDao().insertIgnore(it) }
    }
    private suspend fun memory(id: String, frameId: String) = db.memoryDao().insert(
        MemoryEntity(id, frameId, "text", "fact", "", MemoryTier.SHORT, 0.9, ReviewState.OK, true, now, false, "text"),
    )
    private suspend fun state(id: String) = db.frameStateDao().get(id)?.state
    private fun adoption() = FrameAdoption(db, prefs, { now })

    /** Review Focus 2: pre-v2 inbox files and archived frames awaiting extraction are adopted exactly once. */
    @Test fun preV2InboxFramesAreAdoptedOnce() = runBlocking {
        frame("100_rokid_1_a")
        File(inbox, "rokid_2_b.jpg").apply { writeBytes(byteArrayOf(3)); setLastModified(now) }

        val first = adoption().run()
        assertEquals(1, first.pending)
        assertEquals("PENDING", state("100_rokid_1_a"))

        // the inbox file gets its PENDING row in the drain's own commit transaction
        val ids = RokidFrameInbox.ingestPendingIds(ctx, nowMs = { now }, dirSync = { true })
        assertEquals(1, ids.size)
        assertEquals("PENDING", state(ids.single()))

        val again = adoption().run()
        assertTrue(again.alreadyDone)
        assertEquals(2, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM frame_state").use { it.moveToFirst(); it.getInt(0) })
        assertTrue(prefs.getBoolean(FrameAdoption.FLAG, false))
    }

    @Test fun archivedAwaitingFramesAdopted() = runBlocking {
        frame("old1", ts = 1); frame("old2", ts = 2) // their 24 h queue jobs expired long ago
        frame("gone", withFile = false)
        val r = adoption().run()
        assertEquals(2, r.pending); assertEquals(1, r.missingFile)
        assertEquals("PENDING", state("old1")); assertEquals("PENDING", state("old2"))
        assertNull(state("gone"))
    }

    @Test fun extractedFramesNotRequeued() = runBlocking {
        frame("done1"); memory("m1", "done1")
        frame("done2"); db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m2", "done2", null, "PRIMARY")))
        val r = adoption().run()
        assertEquals(2, r.extracted); assertEquals(0, r.pending)
        assertEquals("EXTRACTED", state("done1")); assertEquals("EXTRACTED", state("done2"))
    }

    @Test fun tombstonedFramesNotAdopted() = runBlocking {
        frame("t1"); db.forgetDao().insertTombstone(ForgetTombstoneEntity("t1", now))
        frame("t2"); memory("m3", "t2"); db.forgetDao().insertTombstone(ForgetTombstoneEntity("t2", now))
        adoption().run()
        assertNull(state("t1")); assertNull(state("t2"))
    }

    /** Review B M-5: a Delete all that lands between the page read and its write leaves no frame_state row for a wiped frame. */
    @Test fun adoptionRacingDeleteAllLeavesNoStateRows() = runBlocking {
        frame("w1"); frame("w2")
        var gen = 0L
        val racing = FrameAdoption(db, prefs, { now }, fileExists = {
            // the wipe starts after the page was read: generation first, rows deleted next
            gen++
            runBlocking { db.frameDao().deleteByIds(listOf("w1", "w2")) }
            true
        }, generation = { gen })
        val r = racing.run()
        assertEquals(0, r.pending)
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM frame_state").use { it.moveToFirst(); it.getInt(0) })
    }
}
