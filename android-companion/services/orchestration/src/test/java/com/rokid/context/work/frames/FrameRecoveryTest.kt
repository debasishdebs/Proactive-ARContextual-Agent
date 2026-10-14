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
        row("old", "EXTRACTING", now - 6 * 60_000L)
        row("fresh", "EXTRACTING", now - 60_000L)
        row("last", "EXTRACTING", now - 10 * 60_000L, attempts = 4)
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
}
