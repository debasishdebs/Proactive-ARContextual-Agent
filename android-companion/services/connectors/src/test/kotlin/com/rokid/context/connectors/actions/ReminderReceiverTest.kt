package com.rokid.context.connectors.actions

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderReceiverTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** Fix round 1 I-3: the lock screen shows a generic "Reminder", never the title or note. */
    @Test fun reminderIsPrivateOnTheLockScreen() {
        ReminderReceiver().onReceive(ctx, Intent().putExtra(ReminderExecutor.EXTRA_ID, "p1").putExtra(ReminderExecutor.EXTRA_TITLE, "Call mom"))
        val n = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications.single()
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Call mom", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Reminder", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(null, n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT))
    }
}
