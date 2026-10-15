package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameRecoveryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val now = 1_793_000_000_000L

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun row(id: String, state: String, claimedAt: Long?, attempts: Int = 0) =
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, state, attempts, null, claimedAt, claimedAt?.let { "c-$id" }, 0)))

    @Test fun abandonedClaimsRequeued() = runBlocking {
        row("old", "EXTRACTING", now - FrameRecovery.CLAIM_TIMEOUT_MS - 60_000L)
        row("fresh", "EXTRACTING", now - FrameRecovery.CLAIM_TIMEOUT_MS + 60_000L)
        row("last", "EXTRACTING", now - FrameRecovery.CLAIM_TIMEOUT_MS - 5 * 60_000L, attempts = 4)
        row("pend", "PENDING", null)
        var signalled = 0
        assertEquals(2, FrameRecovery.run(db.frameStateDao(), now) { signalled++ })
        assertEquals(1, signalled)
        val old = db.frameStateDao().get("old")!!
        assertEquals("PENDING", old.state); assertEquals(1, old.attempts); assertNull(old.claimId); assertNull(old.claimedAt)
        assertEquals("EXTRACTING", db.frameStateDao().get("fresh")!!.state) // a live claim is left alone
        assertEquals("FAILED_FINAL", db.frameStateDao().get("last")!!.state) // the fifth attempt is final
        assertEquals("PENDING", db.frameStateDao().get("pend")!!.state)
    }

    @Test fun newProcessReleasesEveryExtractingRowAtOnceWithoutAnAttempt() = runBlocking {
        row("fresh", "EXTRACTING", now - 60_000L, attempts = 2) // far younger than the 15-minute timeout
        row("old", "EXTRACTING", now - FrameRecovery.CLAIM_TIMEOUT_MS - 60_000L, attempts = 4)
        row("forgotten", "EXTRACTING", now - 60_000L)
        row("pend", "PENDING", null)
        db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone(frameId, createdAt) VALUES ('forgotten', 1)")
        assertEquals(2, FrameRecovery.releaseAtProcessStart(db.frameStateDao(), now, processStartMs = now))
        for ((id, attempts) in listOf("fresh" to 2, "old" to 4)) {
            val r = db.frameStateDao().get(id)!!
            assertEquals("PENDING", r.state); assertEquals(attempts, r.attempts); assertNull(r.claimId); assertNull(r.claimedAt)
        }
        assertEquals("EXTRACTING", db.frameStateDao().get("forgotten")!!.state) // a tombstoned frame is never extracted again
        assertEquals("PENDING", db.frameStateDao().get("pend")!!.state)
    }

    /** Fix wave I5: the legacy backstop claims without the pipeline; its live claim in this process must survive the start release. */
    @Test fun aClaimMadeInThisProcessIsNotReleasedByTheStartRelease() = runBlocking {
        val processStart = now
        row("dead", "EXTRACTING", processStart - 60_000L)       // claimed by the process that died
        row("live", "EXTRACTING", processStart + 2_000L)        // claimed by a legacy worker of this process before the first release
        assertEquals(1, FrameRecovery.releaseAtProcessStart(db.frameStateDao(), processStart + 5_000L, processStart))
        assertEquals("PENDING", db.frameStateDao().get("dead")!!.state)
        assertEquals("EXTRACTING", db.frameStateDao().get("live")!!.state)
        assertEquals("c-live", db.frameStateDao().get("live")!!.claimId)
    }

    @Test fun aNewProcessPipelineReleasesBeforeItsFirstDrain() = runBlocking {
        db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("dead", 1_000L, "/x/dead.jpg", "rokid_glasses"))
        row("dead", "EXTRACTING", now - 30_000L)
        val calls = mutableListOf<List<String>>()
        val p = KeyframePipeline(db, { ids, claim, _ ->
            calls += ids
            ids.forEach { db.frameStateDao().completeIfClaimed(it, claim, "EXTRACTED", now) }
            ExtractOutcome.Saved(emptyList())
        }, { now }, releaseAtStart = true, processStartMs = { now })
        assertEquals(1, p.drainOnce())
        assertEquals(listOf(listOf("dead")), calls)
        assertEquals(0, db.frameStateDao().get("dead")!!.attempts)
    }
}
