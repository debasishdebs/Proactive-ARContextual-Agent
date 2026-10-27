package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.canvas.plan.CanvasForgetCoordinator
import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.CanvasSlice3Forget
import com.rokid.context.orchestration.MemoryExport
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

/** Review fix (Codex recheck): a time-range Forget runs the SAME post-commit cleanup as a fact or photo Forget: cached export, armed reminders, notifications, typed production outcome. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RangeForgetCleanupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val sec = 1_761_000_000L
    private val ms = sec * 1000

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); MemoryExport.productionBuild = { true }; MemoryExport.dir(ctx).deleteRecursively() }
    @After fun tearDown() {
        MemoryExport.testDelete = null; MemoryExport.productionBuild = MemoryExport.defaultProductionBuild; MemoryExport.dir(ctx).deleteRecursively()
        ctx.getSharedPreferences(MemoryExport.CLEANUP_PREFS, Context.MODE_PRIVATE).edit().clear().commit(); graph.db.close(); MemoryGraph.resetForTests(null)
    }

    private fun coordinator(withCleanup: Boolean = true) = CanvasForgetCoordinator(
        CanvasSlice3Forget(graph.db), ctx.filesDir, CollectSession.of(ctx), send = {},
        afterCascade = if (withCleanup) { r -> ForgetActions.afterRange(ctx, r) } else { _ -> true },
    )

    private suspend fun world(): Triple<File, String, android.app.NotificationManager> {
        graph.db.memoryDao().insert(MemoryEntity("A", null, "secret", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, ms + 500, false, "secret")) // created inside the range
        graph.asks.add(com.rokid.context.memory.db.AskEntity("ask1", 1, "q", "a", "[\"A\"]", null, "phone", false))
        val pid = "prop_ASK_ask1"
        val r = com.rokid.context.connectors.actions.ActionParams.Reminder("Call the clinic", System.currentTimeMillis() + 3_600_000, "about the results")
        graph.proposals.addIgnore(com.rokid.context.memory.db.ProposalEntity(pid, "ASK", "ask1", "REMINDER", com.rokid.context.connectors.actions.ActionParams.encode(r), "EXECUTED", 0, "auto", "alarm:$pid", 1))
        com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx).arm(pid, r)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("t", "t", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(pid, com.rokid.context.connectors.actions.ProposalNotifications.PROPOSAL_ID, android.app.Notification.Builder(ctx, "t").setSmallIcon(android.R.drawable.ic_menu_add).setContentTitle("Call the clinic").build())
        val export = File(MemoryExport.dir(ctx).apply { mkdirs() }, "proactive-context-memories-1.json").apply { writeText("{\"memories\":[{\"text\":\"secret\"}]}") }
        return Triple(export, pid, nm)
    }

    @Test fun aRangeForgetDeletesTheCachedExportTheReminderAndTheNotification() = runBlocking<Unit> {
        val (export, _, nm) = world()
        val alarms = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetRangeOutcome(ctx, coordinator(), ms, ms + 999))
        assertFalse("the cached export", export.exists()); assertEquals("the armed reminder", 0, alarms.scheduledAlarms.size)
        assertTrue("the notification", org.robolectric.Shadows.shadowOf(nm).activeNotifications.isEmpty())
    }

    @Test fun inProductionAnUndeletableExportMakesTheRangeForgetTypedAndSetsTheRetry() = runBlocking<Unit> {
        val (export, _, _) = world(); MemoryExport.testDelete = { false }
        assertEquals(ForgetActions.Outcome.CACHE_CLEANUP_FAILED, ForgetActions.forgetRangeOutcome(ctx, coordinator(), ms, ms + 999))
        assertTrue(export.exists()); assertTrue(MemoryExport.cleanupPending(ctx))
        MemoryExport.productionBuild = { false }
        assertEquals("a debug build may leave it", ForgetActions.Outcome.DONE, ForgetActions.forgetRangeOutcome(ctx, coordinator(), ms, ms + 999))
    }

    @Test fun withoutTheCleanupHookTheLeftoversStay() = runBlocking<Unit> { // pins what the hook is for
        val (export, _, _) = world()
        ForgetActions.forgetRangeOutcome(ctx, coordinator(withCleanup = false), ms, ms + 999)
        assertTrue(export.exists())
    }
}
