package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.MomentForgetFixture
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.moment.MomentId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P0 retention fix, the "26 Nov 00:50 cannot lose anything" proof: a golden world whose SHORT facts (older than the window) back a nudge (with a `dependsOn`
 * chain), an answer, a stored Moment and a Scene. The real [RetentionWorker] runs; the facts go, and nothing that depended on them loses text or rows.
 * Both `forget_v2` modes. An explicit user Forget of the same fact still redacts (control).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionNeverLosesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val hour = 3_600_000L
    private val graph get() = MemoryGraph.get(ctx)
    private val sec = MomentForgetFixture.SEC
    private val mom = MomentId.format(sec + 100)
    private val now = System.currentTimeMillis()

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { ForgetV2.testOverride = null; graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun seed(): MomentForgetFixture {
        val fx = MomentForgetFixture(graph.db, now)
        val old = now - 30 * hour // SHORT window is 24 h by default
        fx.frame("f1", now - 30 * hour, wire = "w1", capturedAt = now - 30 * hour)
        fx.memory("fact1", "f1", createdAt = old); fx.memory("fact2", "f1", createdAt = old); fx.memory("fact3", null, createdAt = old)
        graph.db.memoryDao().insert(com.rokid.context.memory.db.MemoryEntity("cap1", "f1", "caption", "caption", "", MemoryTier.LONG, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, old, false, "caption"))
        graph.db.askDao().upsert(AskEntity("a1", 1, "q", "ANSWER TEXT", """["fact1","fact3"]""", null, "test", false))
        graph.db.nudgeDao().insertIgnore(NudgeEntity("n1", 1, "NUDGE TEXT", "WHY TEXT", """["fact2",{"dependsOn":["fact3"]}]""", 1, "h1", "PHONE", false))
        fx.project(mom, "FRAME" to "f1", "MEMORY" to "fact1", "MEMORY" to "fact2")
        graph.db.sceneDao().replace(
            SceneEntity("sc1", 1, 0L, MomentId.startMs(mom)!!, MomentId.startMs(mom)!! + 999, SceneRule.FRAME_CHANGE, null, null, "STILL", 1, 1L),
            listOf(ScenePartEntity("sc1", ScenePartKind.MOMENT, mom, "r", MomentId.startMs(mom)!!)),
        )
        graph.proposals.create(ProposalEntity("pp", ProposalOrigin.NUDGE.name, "n1", "REMINDER", """{"title":"t"}""", ProposalState.PENDING.name, 99_999, null, null, 1), 1)
        return fx
    }

    private fun assertNothingLost() = runBlocking {
        assertTrue(graph.db.memoryViewDao().byId("fact1") == null && graph.db.memoryViewDao().byId("fact2") == null && graph.db.memoryViewDao().byId("fact3") == null)
        assertNotNull("the LONG caption is not a tier-expiry candidate yet", graph.db.memoryViewDao().byId("cap1"))
        val a = graph.db.askDao().byId("a1")!!
        assertFalse(a.redacted); assertEquals("ANSWER TEXT", a.answer); assertEquals("""["fact1","fact3"]""", a.sourcesJson)
        val n = graph.db.nudgeDao().byIds(listOf("n1")).single()
        assertFalse(n.redacted); assertEquals("NUDGE TEXT", n.text); assertEquals("WHY TEXT", n.why); assertEquals("""["fact2",{"dependsOn":["fact3"]}]""", n.sourceIdsJson)
        assertEquals("the stored Moment stays", listOf(mom), graph.db.momentDao().momentIdsBetween(mom, mom))
        assertTrue("the Scene stays", graph.db.sceneDao().sceneIdsHoldingMoments(listOf(mom)).contains("sc1"))
        assertEquals(ProposalState.PENDING.name, graph.proposals.byId("pp")!!.state)
        assertEquals("""{"title":"t"}""", graph.proposals.byId("pp")!!.paramsJson)
    }

    private fun run() = runBlocking { assertTrue(TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() is ListenableWorker.Result.Success) }

    @Test fun retentionLosesNothingDependentLegacyPath() = runBlocking { ForgetV2.testOverride = false; seed(); run(); assertNothingLost() }
    @Test fun retentionLosesNothingDependentV2Path() = runBlocking { ForgetV2.testOverride = true; seed(); run(); assertNothingLost() }

    /** Control: an explicit user Forget of the same fact still redacts. */
    @Test fun userForgetStillRedacts() = runBlocking {
        ForgetV2.testOverride = false; seed()
        com.rokid.context.memory.forget.ForgetService(graph.db, graph.proposals).forgetFact("fact1")
        assertTrue(graph.db.askDao().byId("a1")!!.redacted)
    }
}
