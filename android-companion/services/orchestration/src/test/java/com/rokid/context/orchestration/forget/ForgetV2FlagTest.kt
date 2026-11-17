package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
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

/** Forget lifecycle M4: `forgetV2` is OFF by default, lives in the `pc_dev` preferences, and with it ON a real Forget through `ForgetActions` deletes what v1 deletes and the executor revokes the leases. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetV2FlagTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = null }
    @After fun tearDown() { DeveloperFlags.setForgetV2(ctx, false); ForgetV2.testOverride = null; Leases.shared.revokeAll(); graph.db.close(); MemoryGraph.resetForTests(null) }

    @Test fun theFlagIsOffByDefaultAndLivesInTheBackupExcludedDeveloperPreferences() {
        assertEquals("pc_dev", DeveloperFlags.PREFS); assertEquals(ForgetV2.PREFS, DeveloperFlags.PREFS); assertEquals(ForgetV2.KEY, DeveloperFlags.FORGET_V2)
        assertFalse(DeveloperFlags.forgetV2(ctx))
        graph // installs the reader
        assertFalse(ForgetV2.on)
        DeveloperFlags.setForgetV2(ctx, true)
        assertTrue(ForgetV2.on)
        DeveloperFlags.setForgetV2(ctx, false)
        assertFalse(ForgetV2.on)
    }

    @Test fun aRealPhotoForgetWithTheFlagOnDeletesTheV1SetAndRevokesTheLeases() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/v2.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f_v2", 1, jpg.absolutePath, "rokid_glasses"))
        graph.db.memoryDao().insert(MemoryEntity("m_v2", "f_v2", "V2-SECRET", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "V2-SECRET"))
        graph.db.memoryDao().insert(MemoryEntity("m_v2_derived", null, "d", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, true, "d"))
        graph.db.forgetDao().insertEdges(listOf(MemorySourceEntity("m_v2_derived", "m_v2")))
        graph.asks.add(AskEntity("ask_v2", 1, "q", "a", "[\"m_v2\"]", null, "phone", false))
        val lease = Leases.shared.open(Leases.Kind.ASK, listOf(Ref(Family.MEMORY, "m_v2_derived")), 0)
        DeveloperFlags.setForgetV2(ctx, true)
        assertTrue(ForgetActions.forgetMoment(ctx, "f_v2"))
        assertTrue("the executor revoked the lease its plan reached", lease.isRevoked)
        assertEquals(null, graph.db.forgetDao().frame("f_v2")); assertTrue(graph.db.forgetDao().memories(listOf("m_v2")).isEmpty())
        assertTrue("invalidated (or already rebuilt away by the re-summarise)", graph.db.forgetDao().memories(listOf("m_v2_derived")).all { it.reviewState == ReviewState.INVALIDATED })
        assertEquals(ForgetService.REDACTED, graph.asks.byId("ask_v2")!!.answer)
        assertFalse(jpg.exists())
        lease.close()
    }
}
