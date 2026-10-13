package com.rokid.context.phone.actions

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ProposalNotifierTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val notifier = ProposalNotifier(ctx) { ZoneId.of("UTC") }

    @After fun close() = db.close()

    private fun row(id: String, state: ProposalState) = ProposalEntity(
        id, "ASK", "a-$id", "SHOPPING_ADD", """{"items":[{"name":"milk","qty":null,"unit":null}]}""", state.name, Long.MAX_VALUE, null, null, 1,
    )

    @Test fun cancelledOnResolve() = runBlocking {
        notifier.sync(listOf(row("p1", ProposalState.PENDING)))
        val posted = shadowOf(nm).activeNotifications
        assertEquals(listOf("p1"), posted.map { it.tag })
        assertEquals(listOf("Add", "No thanks"), posted.single().notification.actions.map { it.title.toString() })
        notifier.sync(emptyList()) // p1 went EXECUTED: no longer pending
        assertEquals(0, shadowOf(nm).activeNotifications.size)
    }

    @Test fun noNotificationPermissionSkipsSilently() = runBlocking {
        shadowOf(nm).setNotificationsEnabled(false)
        notifier.sync(listOf(row("p1", ProposalState.PENDING)))
        assertEquals(0, shadowOf(nm).activeNotifications.size)
        @Suppress("UNUSED_VARIABLE") val unused = repo
    }

    /** Fix round 1 I-3: nothing about the action on the lock screen, and Add / No thanks need an unlocked phone. */
    @Test fun lockScreenSafe() = runBlocking {
        notifier.sync(listOf(row("p1", ProposalState.PENDING)))
        val n = shadowOf(nm).activeNotifications.single().notification
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Suggested action", n.publicVersion.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(null, n.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TEXT))
        assertEquals(listOf(true, true), n.actions.map { it.isAuthenticationRequired })
    }
}
