package com.rokid.context.connectors.actions

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.connectors.actions.executors.reminder
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderReceiverTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val nm get() = shadowOf(ctx.getSystemService(NotificationManager::class.java))

    @After fun close() = db.close()

    private fun fired(id: String) = Intent().putExtra(ReminderExecutor.EXTRA_ID, id).putExtra(ReminderExecutor.EXTRA_TITLE, "Call mom")

    /** Fix round 1 I-3: the lock screen shows a generic "Reminder", never the title or note. */
    @Test fun reminderIsPrivateOnTheLockScreen() = runBlocking {
        repo.addIgnore(reminder("p1", System.currentTimeMillis() + 60_000, ProposalState.EXECUTED))
        ReminderReceiver.show(ctx, fired("p1"), repo)
        val n = nm.allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Call mom", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Reminder", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(null, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    /** Privacy carry: a fired alarm re-reads its proposal; redacted, undone (DECLINED) or missing posts nothing. */
    @Test fun aFiredAlarmForARedactedOrUndoneProposalShowsNothing() = runBlocking {
        val due = System.currentTimeMillis() + 60_000
        repo.addIgnore(reminder("red", due, ProposalState.EXECUTED).copy(paramsJson = ForgetService.REDACTED_PARAMS))
        repo.addIgnore(reminder("undone", due, ProposalState.DECLINED))
        ReminderReceiver.show(ctx, fired("red"), repo)
        ReminderReceiver.show(ctx, fired("undone"), repo)
        ReminderReceiver.show(ctx, fired("missing"), repo)
        assertEquals(0, nm.allNotifications.size)
    }

    /**
     * Codex r21 P1-5: a Forget (or Delete all) that commits, and cancels the reminder's notifications, between the live check and the post can never be
     * followed by a post of its text: the receiver re-reads after posting and cancels its own notification.
     */
    @Test fun aForgetThatCompletesBetweenTheCheckAndThePostLeavesNoNotification() = runBlocking {
        repo.addIgnore(reminder("p1", System.currentTimeMillis() + 60_000, ProposalState.EXECUTED))
        ReminderReceiver.show(ctx, fired("p1"), repo, afterCheck = {
            // the Forget: the redaction commits, then its cleanup cancels the (not yet posted) notification
            db.openHelper.writableDatabase.execSQL("UPDATE proposal SET paramsJson = ? WHERE id = ?", arrayOf<Any>(ForgetService.REDACTED_PARAMS, "p1"))
            ProposalNotifications.cancel(ctx, listOf("p1"))
        })
        assertEquals("nothing of the forgotten reminder is left on screen", 0, nm.allNotifications.size)
    }
}

