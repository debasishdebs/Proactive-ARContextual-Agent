package com.rokid.context.connectors.actions

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.connectors.actions.executors.reminder
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BootReceiverTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)

    @After fun close() = db.close()

    @Test fun rearmsOnlyFutureExecutedReminders() = runBlocking {
        val now = System.currentTimeMillis()
        repo.addIgnore(reminder("past", now - 60_000, ProposalState.EXECUTED))
        repo.addIgnore(reminder("future", now + 3_600_000, ProposalState.EXECUTED))
        repo.addIgnore(reminder("failed", now + 3_600_000, ProposalState.FAILED))
        assertEquals(1, BootReceiver.rearm(repo, ReminderExecutor(ctx), now))
        val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(listOf(now + 3_600_000), alarms.map { it.triggerAtMs })
    }

    /** Integration carry 2: a reminder whose params Forget redacted is never re-armed. */
    @Test fun redactedRemindersAreNotRearmed() = runBlocking {
        val now = System.currentTimeMillis()
        repo.addIgnore(reminder("future", now + 3_600_000, ProposalState.EXECUTED).copy(paramsJson = com.rokid.context.memory.forget.ForgetService.REDACTED_PARAMS))
        assertEquals(0, BootReceiver.rearm(repo, ReminderExecutor(ctx), now))
        assertEquals(0, shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms.size)
    }
}
