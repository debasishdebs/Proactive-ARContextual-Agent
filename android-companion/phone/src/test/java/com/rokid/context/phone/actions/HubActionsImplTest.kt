package com.rokid.context.phone.actions

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.ai.contract.ShoppingLine
import com.rokid.context.connectors.actions.ActionEngine
import com.rokid.context.connectors.actions.ActionSettingsStore
import com.rokid.context.connectors.actions.ActionToggle
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.connectors.actions.executors.ActionExecutor
import com.rokid.context.connectors.actions.executors.ExecResult
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubActionsImplTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val settings = ActionSettingsStore(ctx.getSharedPreferences("t_hub_actions", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val executed = AtomicInteger()
    private val exec = object : ActionExecutor {
        override suspend fun execute(p: ProposalEntity): ExecResult { executed.incrementAndGet(); return ExecResult.Done("ref") }
        override suspend fun undo(p: ProposalEntity) = true
    }
    private val zone = ZoneId.of("UTC")
    private val engine = ActionEngine(
        repo, settings, ActionType.entries.associateWith { exec }, { Verbosity.MEDIUM }, scope, Dispatchers.IO, { scheduler.currentTime }, zone = { zone },
    )
    private val hub = HubActionsImpl(engine, repo, scope, zone = { zone })

    @After fun close() { scope.cancel(); db.close() }

    private val tomorrow = LocalDateTime.of(2030, 1, 1, 18, 0)

    private suspend fun proposeReminder(): String {
        scheduler.runCurrent() // the hub subscribes to engine events
        val id = engine.propose(ProposalOrigin.ASK, "a1", ProposedAction.Reminder("Call mom", tomorrow, null), scheduler.currentTime)!!.proposalId
        settle()
        return id
    }

    /** Lets Room (IO) and the test dispatcher hand work back and forth. */
    private fun settle(cond: () -> Boolean = { true }) {
        val until = System.currentTimeMillis() + 3_000
        do { scheduler.runCurrent(); Thread.sleep(20) } while (!cond() && System.currentTimeMillis() < until)
        scheduler.runCurrent()
    }

    @Test fun confirmOverlayShowsThenClearsOnPhoneDecline() = runBlocking {
        val id = proposeReminder()
        settle { hub.overlay.value != null }
        assertEquals(Overlay.Confirm(id, "Remind me?", "Call mom \u00b7 Tue 18:00"), hub.overlay.value)
        engine.respond(id, yes = false, by = "phone")
        settle { hub.overlay.value == null }
        assertNull(hub.overlay.value)
    }

    @Test fun secondHudVoteIsNoOp() = runBlocking {
        val id = proposeReminder()
        settle { hub.overlay.value != null }
        hub.onConfirm(id, true)
        hub.onConfirm(id, true)
        settle { executed.get() == 1 && hub.overlay.value == null }
        settle()
        assertEquals(1, executed.get())
        assertNull(hub.overlay.value)
    }

    @Test fun undoToastLastsTenSeconds() = runBlocking {
        settings.set(ActionType.SHOPPING_ADD, ActionToggle(enabled = true, alwaysConfirm = false))
        scheduler.runCurrent()
        val id = engine.propose(ProposalOrigin.ASK, "a2", ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", null, null))), 0)!!.proposalId
        settle { hub.overlay.value != null }
        assertEquals(Overlay.Toast("Added \u00b7 double-tap undo", undoProposalId = id), hub.overlay.value)
        scheduler.advanceTimeBy(9_900); scheduler.runCurrent()
        assertTrue(hub.overlay.value is Overlay.Toast)
        scheduler.advanceTimeBy(200); scheduler.runCurrent()
        assertNull(hub.overlay.value)
    }

    /** "note that…" (user notes): an ask for a turned-off type says so on the HUD for a few seconds, with no undo. */
    @Test fun offNoticeToastForAnAskOnATurnedOffType() = runBlocking {
        settings.set(ActionType.NOTE_ADD, ActionToggle(enabled = false))
        scheduler.runCurrent()
        engine.propose(ProposalOrigin.ASK, "a3", ProposedAction.NoteAdd("car on level 3"), 0)
        settle { hub.overlay.value != null }
        assertEquals(Overlay.Toast("Notes are off. Turn them on in the phone app: Settings \u2192 Actions."), hub.overlay.value)
        scheduler.advanceTimeBy(HubActionsImpl.OFF_NOTICE_MS - 100); scheduler.runCurrent()
        assertTrue(hub.overlay.value is Overlay.Toast)
        scheduler.advanceTimeBy(200); scheduler.runCurrent()
        assertNull(hub.overlay.value)
    }

    /** BUG-R2-5: the toast hidden under the ask's Thinking still gets its full 10 s once it reaches the glasses. */
    @Test fun undoToastGetsTenSecondsFromWhenItFirstShows() = runBlocking {
        settings.set(ActionType.SHOPPING_ADD, ActionToggle(enabled = true, alwaysConfirm = false))
        scheduler.runCurrent()
        val id = engine.propose(ProposalOrigin.ASK, "a5", ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", null, null))), 0)!!.proposalId
        settle { hub.overlay.value != null }
        scheduler.advanceTimeBy(6_000); scheduler.runCurrent()
        hub.onShown(Overlay.Listening("thinking")) // still under Thinking: not shown
        scheduler.advanceTimeBy(1_000); scheduler.runCurrent()
        hub.onShown(Overlay.Answer("q", "a", speaking = false, undoProposalId = id)) // 7 s in: first on the glasses
        settle { engine.undoUntil(id) == 17_000L }
        assertEquals(17_000L, engine.undoUntil(id))
        hub.onShown(Overlay.Toast("Added \u00b7 double-tap undo", undoProposalId = id)) // later frames change nothing
        settle()
        assertEquals(17_000L, engine.undoUntil(id))
        scheduler.advanceTimeBy(9_800); scheduler.runCurrent() // 16.8 s
        assertTrue(hub.overlay.value is Overlay.Toast)
        scheduler.advanceTimeBy(400); scheduler.runCurrent() // 17.2 s
        assertNull(hub.overlay.value)
    }

    @Test fun shoppingDetailShowsTwoNamesPlusCount() = runBlocking {
        scheduler.runCurrent()
        val items = listOf("milk", "eggs", "bread", "jam").map { ShoppingLine(it, null, null) }
        engine.propose(ProposalOrigin.ASK, "a3", ProposedAction.ShoppingAdd(items), 0)
        settle { hub.overlay.value != null }
        assertEquals("milk, eggs +2", (hub.overlay.value as Overlay.Confirm).detail)
        assertEquals("Add to shopping list?", (hub.overlay.value as Overlay.Confirm).title)
    }

    @Test fun longTitleIsEllipsisedToSixty() = runBlocking {
        scheduler.runCurrent()
        engine.propose(ProposalOrigin.ASK, "a4", ProposedAction.Reminder("x".repeat(80), tomorrow, null), 0)
        settle { hub.overlay.value != null }
        val detail = (hub.overlay.value as Overlay.Confirm).detail
        assertEquals(60, detail.length)
        assertTrue(detail.endsWith("\u2026"))
    }
}
