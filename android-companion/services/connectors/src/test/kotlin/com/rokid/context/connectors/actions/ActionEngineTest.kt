package com.rokid.context.connectors.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.ai.contract.ShoppingLine
import com.rokid.context.connectors.actions.executors.ActionExecutor
import com.rokid.context.connectors.actions.executors.ExecResult
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.Resolve
import com.rokid.context.memory.db.ActionLogEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Fake executor: counts effects; [throwOnExecute] simulates a crashing executor. */
class CountingExecutor(private val throwOnExecute: Boolean = false) : ActionExecutor {
    val executed = AtomicInteger()
    val undone = AtomicInteger()
    override suspend fun execute(p: ProposalEntity): ExecResult {
        if (throwOnExecute) error("boom")
        executed.incrementAndGet()
        return ExecResult.Done("ref:${p.id}")
    }
    override suspend fun undo(p: ProposalEntity): Boolean { undone.incrementAndGet(); return true }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val settings = ActionSettingsStore(ctx.getSharedPreferences("t_actions", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private var now = NOW
    private val zone = ZoneId.of("Europe/Berlin")

    @After fun close() { scope.cancel(); db.close() }

    private fun engine(
        exec: ActionExecutor = CountingExecutor(), verbosity: Verbosity = Verbosity.MEDIUM, clock: () -> Long = { now }, zoneOf: () -> ZoneId = { zone },
    ) = ActionEngine(
        repo, settings, ActionType.entries.associateWith { exec }, { verbosity }, scope, Dispatchers.IO, clock, zone = zoneOf,
        audit = { id, tr, d, ts -> db.actionDao().log(ActionLogEntity(ts = ts, proposalId = id, transition = tr, detail = d)) },
    )

    private fun reminderIn(ms: Long) = ProposedAction.Reminder("Call mom", LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now + ms), zone), null)

    @Test fun disabledTypeIsSuppressed() = runBlocking {
        val exec = CountingExecutor()
        settings.set(ActionType.REMINDER, ActionToggle(enabled = false))
        val p = engine(exec).propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        assertEquals(ProposalState.SUPPRESSED, p.state)
        assertEquals(HudSurface.NONE, p.hud)
        assertEquals(0, exec.executed.get())
        assertEquals(ProposalState.SUPPRESSED.name, repo.byId(p.proposalId)!!.state)
    }

    @Test fun askConfirmShowsHudConfirmAtAnyVerbosity() = runBlocking {
        val p = engine(verbosity = Verbosity.SILENT).propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        assertEquals(ProposalState.PENDING, p.state)
        assertEquals(HudSurface.CONFIRM, p.hud)
        assertEquals(now + 60_000, repo.byId(p.proposalId)!!.expiresAt)
    }

    @Test fun nudgeConfirmHudOnlyAtMax() = runBlocking {
        val medium = engine(verbosity = Verbosity.MEDIUM).propose(ProposalOrigin.NUDGE, "n1", reminderIn(3_600_000), now)!!
        assertEquals(HudSurface.NONE, medium.hud)
        val max = engine(verbosity = Verbosity.MAX).propose(ProposalOrigin.NUDGE, "n2", reminderIn(3_600_000), now)!!
        assertEquals(HudSurface.CONFIRM, max.hud)
        assertEquals(now + 600_000, repo.byId(max.proposalId)!!.expiresAt)
    }

    @Test fun noConfirmExecutesImmediatelyWithUndoWindow() = runBlocking {
        val exec = CountingExecutor()
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val e = engine(exec)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        assertEquals(ProposalState.EXECUTED, p.state)
        assertEquals(HudSurface.UNDO_TOAST, p.hud)
        assertEquals(now + 10_000, p.undoUntilMs)
        assertEquals(1, exec.executed.get())
        assertEquals("ref:${p.proposalId}", repo.byId(p.proposalId)!!.createdRef)
        // Undo inside the window works once; outside it does nothing.
        now += 9_000
        assertEquals(true, e.undo(p.proposalId, "hud"))
        assertEquals("undo:hud", repo.byId(p.proposalId)!!.resolvedBy)
        assertEquals(false, e.undo(p.proposalId, "hud"))
        assertEquals(1, exec.undone.get())
    }

    @Test fun undoAfterWindowIsRefused() = runBlocking {
        val exec = CountingExecutor()
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val e = engine(exec)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        now += 10_000
        assertEquals(false, e.undo(p.proposalId, "hud"))
        assertEquals(ProposalState.EXECUTED.name, repo.byId(p.proposalId)!!.state)
        assertEquals(0, exec.undone.get())
    }

    @Test fun pastDueAtStoredExpired() = runBlocking {
        val exec = CountingExecutor()
        val p = engine(exec).propose(ProposalOrigin.ASK, "a1", reminderIn(-5_000), now)!!
        assertEquals(ProposalState.EXPIRED, p.state)
        assertEquals(HudSurface.NONE, p.hud)
        assertEquals(0, exec.executed.get())
        assertEquals(listOf("CREATED:EXPIRED", "PAST"), db.actionDao().logFor(p.proposalId).map { it.transition })
    }

    @Test fun dstGapDueAtShiftsForward() = runBlocking {
        now = ZonedDateTime.of(2027, 3, 27, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val gap = LocalDateTime.of(2027, 3, 28, 2, 30) // Berlin springs forward 02:00 -> 03:00 on 2027-03-28
        val p = engine().propose(ProposalOrigin.ASK, "a1", ProposedAction.Reminder("x", gap, null), now)!!
        val params = ActionParams.decode(ActionType.REMINDER, repo.byId(p.proposalId)!!.paramsJson) as ActionParams.Reminder
        assertEquals(ZonedDateTime.of(2027, 3, 28, 3, 30, 0, 0, zone).toInstant().toEpochMilli(), params.dueAtMs)
    }

    @Test fun calendarEndAtNotAfterStartDefaultsToThirtyMinutes() = runBlocking {
        settings.set(ActionType.CALENDAR_EVENT, ActionToggle(enabled = true))
        val start = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now + 3_600_000), zone)
        val p = engine().propose(ProposalOrigin.ASK, "a1", ProposedAction.CalendarEvent("Dentist", start, start, null), now)!!
        val params = ActionParams.decode(ActionType.CALENDAR_EVENT, repo.byId(p.proposalId)!!.paramsJson) as ActionParams.Calendar
        assertEquals(params.startAtMs + 30 * 60_000, params.endAtMs)
    }

    @Test fun duplicateShoppingNamesDeduped() = runBlocking {
        val items = listOf("Milk", "milk ", "\u0926\u0942\u0927", "\u0926\u0942\u0927").map { ShoppingLine(it, null, null) }
        val p = engine().propose(ProposalOrigin.ASK, "a1", ProposedAction.ShoppingAdd(items), now)!!
        val params = ActionParams.decode(ActionType.SHOPPING_ADD, repo.byId(p.proposalId)!!.paramsJson) as ActionParams.Shopping
        assertEquals(listOf("Milk", "\u0926\u0942\u0927"), params.items.map { it.name })
    }

    @Test fun expiryFiresWithoutAnswer() = runBlocking {
        val e = engine(clock = { scheduler.currentTime })
        now = 0
        val p = e.propose(ProposalOrigin.ASK, "a1", ProposedAction.Reminder("x", LocalDateTime.now(zone).plusDays(1), null), 0)!!
        scheduler.advanceTimeBy(60_001)
        waitFor { repo.byId(p.proposalId)!!.state == ProposalState.EXPIRED.name }
        assertEquals("expiry", repo.byId(p.proposalId)!!.resolvedBy)
        assertEquals(Resolve.Lost(ProposalState.EXPIRED), e.respond(p.proposalId, yes = true, by = "hud"))
    }

    @Test fun lateYesNeverExecutes() = runBlocking {
        val exec = CountingExecutor()
        val e = engine(exec)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        now += 60_000
        assertEquals(Resolve.Lost(ProposalState.EXPIRED), e.respond(p.proposalId, yes = true, by = "phone"))
        assertEquals(0, exec.executed.get())
    }

    @Test fun respondYesExecutesOnceAndSecondVoteLoses() = runBlocking {
        val exec = CountingExecutor()
        val e = engine(exec)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        assertEquals(Resolve.Won(ProposalState.EXECUTING), e.respond(p.proposalId, yes = true, by = "hud"))
        assertEquals(ProposalState.EXECUTED.name, repo.byId(p.proposalId)!!.state)
        assertEquals(Resolve.Lost(ProposalState.EXECUTED), e.respond(p.proposalId, yes = false, by = "phone"))
        assertEquals(1, exec.executed.get())
    }

    @Test fun intakeNeverThrows() = runBlocking {
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val id = engine(CountingExecutor(throwOnExecute = true)).onProposed(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)
        assertEquals(ProposalState.FAILED.name, repo.byId(id!!)!!.state)
    }

    @Test fun intakeIsIdempotentPerOrigin() = runBlocking {
        val exec = CountingExecutor()
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val e = engine(exec)
        val first = e.onProposed(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)
        val again = e.onProposed(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)
        assertEquals("prop_ASK_a1", first)
        assertEquals(first, again)
        assertEquals(1, exec.executed.get())
        assertEquals(1, db.proposalDao().let { repo.inState(ProposalState.EXECUTED).size })
    }

    @Test fun intakeReturnsNullWhenNormalisingFails() = runBlocking {
        assertNull(engine(zoneOf = { error("no zone") }).onProposed(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now))
        assertNull(repo.byId("prop_ASK_a1"))
    }

    private fun redacted(id: String, state: ProposalState) = ProposalEntity(
        id, ProposalOrigin.NUDGE.name, "n1", ActionType.REMINDER.name, com.rokid.context.memory.forget.ForgetService.REDACTED_PARAMS, state.name,
        now + 600_000, resolvedBy = null, createdRef = null, createdAt = now,
    )

    /** Integration carry 2: a row whose params Forget redacted never reaches an executor (recover or a late approve). */
    @Test fun redactedProposalsNeverExecute() = runBlocking {
        val exec = CountingExecutor()
        repo.addIgnore(redacted("prop_x", ProposalState.EXECUTING))
        repo.addIgnore(redacted("prop_y", ProposalState.PENDING))
        val e = engine(exec)
        e.recover()
        e.respond("prop_y", yes = true, by = "phone")
        assertEquals(0, exec.executed.get())
        assertEquals(ProposalState.FAILED.name, repo.byId("prop_x")!!.state)
    }

    /** Fix round 1 I-1: recover() racing an in-flight auto-execute must not run the executor a second time. */
    @Test fun recoverSkipsAnInFlightExecution() = runBlocking {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val slow = object : ActionExecutor {
            override suspend fun execute(p: ProposalEntity): ExecResult { runs.incrementAndGet(); entered.complete(Unit); release.await(); return ExecResult.Done("event:1") }
            override suspend fun undo(p: ProposalEntity) = true
        }
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val e = engine(slow)
        val proposing = async(Dispatchers.IO) { e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now) }
        entered.await()
        val recovering = async(Dispatchers.IO) { e.recover() }
        Thread.sleep(300) // recover() reads the EXECUTING row while the first run is still inside the executor
        release.complete(Unit)
        recovering.await()
        assertEquals(ProposalState.EXECUTED, proposing.await()!!.state)
        assertEquals(1, runs.get())
    }

    /** Final review I2: a Done with a warning still ends EXECUTED, and the warning code is logged for the card. */
    @Test fun warningIsLoggedWithTheExecution() = runBlocking {
        val warns = object : ActionExecutor {
            override suspend fun execute(p: ProposalEntity): ExecResult = ExecResult.Done("alarm:${p.id}", com.rokid.context.connectors.actions.executors.FailCode.NOTIFICATIONS_OFF)
            override suspend fun undo(p: ProposalEntity) = true
        }
        val e = engine(warns)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        e.respond(p.proposalId, true, "phone")
        assertEquals(ProposalState.EXECUTED.name, repo.byId(p.proposalId)!!.state)
        val warn = db.actionDao().logFor(p.proposalId).single { it.transition == ActionEngine.WARN }
        assertEquals("NOTIFICATIONS_OFF", warn.detail)
    }

    /** Final review I1: an executor gated until after the caller is cancelled, then arming the real reminder alarm. */
    private class GatedReminder(ctx: Context) : ActionExecutor {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        private val real = com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx)
        override suspend fun execute(p: ProposalEntity): ExecResult { entered.complete(Unit); release.await(); return real.execute(p) }
        override suspend fun undo(p: ProposalEntity) = real.undo(p)
    }

    private fun alarms() = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms

    /** Final review I1: the card's scope is cancelled (user leaves Ask detail) while the approved reminder executes. */
    @Test fun approvedProposalFinishesWhenTheCallerIsCancelled() = runBlocking {
        val gated = GatedReminder(ctx)
        val e = engine(gated)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), now)!!
        assertEquals(ProposalState.PENDING, p.state)
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = caller.launch { e.respond(p.proposalId, true, "phone") }
        gated.entered.await()
        job.cancel()
        gated.release.complete(Unit)
        job.join()
        awaitState(p.proposalId, ProposalState.EXECUTED)
        assertEquals(1, alarms().size)
    }

    /** Final review I1: same for the auto-execute path (NudgeWorker stopped while propose runs). */
    @Test fun autoExecutionFinishesWhenTheCallerIsCancelled() = runBlocking {
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val gated = GatedReminder(ctx)
        val e = engine(gated)
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = caller.launch { e.propose(ProposalOrigin.NUDGE, "n1", reminderIn(3_600_000), now) }
        gated.entered.await()
        job.cancel()
        gated.release.complete(Unit)
        job.join()
        awaitState(ActionEngine.idFor(ProposalOrigin.NUDGE, "n1"), ProposalState.EXECUTED)
        assertEquals(1, alarms().size)
    }

    private suspend fun awaitState(id: String, want: ProposalState) {
        // Bounded wait: a run that outlives the caller may land a moment after join().
        repeat(40) { if (repo.byId(id)?.state == want.name) return; Thread.sleep(50) }
        assertEquals(want.name, repo.byId(id)?.state)
    }

    /** Fix round 1 I-2: the undo window starts when the effect lands, not when the ask arrived. */
    @Test fun slowExecutionStillGetsAFullUndoWindow() = runBlocking {
        val slow = object : ActionExecutor {
            override suspend fun execute(p: ProposalEntity): ExecResult { now += 12_000; return ExecResult.Done("ref") }
            override suspend fun undo(p: ProposalEntity) = true
        }
        settings.set(ActionType.REMINDER, ActionToggle(enabled = true, alwaysConfirm = false))
        val askAt = now
        val e = engine(slow)
        val p = e.propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), askAt)!!
        assertEquals(askAt + 12_000 + 10_000, p.undoUntilMs)
        now += 9_000
        assertEquals(true, e.undo(p.proposalId, "hud"))
    }

    @Test fun pendingExpiryCountsFromHandlingTime() = runBlocking {
        val askAt = now
        now += 5_000
        val p = engine().propose(ProposalOrigin.ASK, "a1", reminderIn(3_600_000), askAt)!!
        assertEquals(askAt + 5_000 + 60_000, repo.byId(p.proposalId)!!.expiresAt)
    }

    @Test fun emptyShoppingListIsRejected() = runBlocking {
        val items = listOf(ShoppingLine("  ", null, null))
        assertNull(engine().propose(ProposalOrigin.ASK, "a1", ProposedAction.ShoppingAdd(items), now))
        assertNull(engine().propose(ProposalOrigin.ASK, "a2", ProposedAction.ShoppingAdd(emptyList()), now))
        assertNull(repo.byId("prop_ASK_a1"))
    }

    @Test fun settingsStoreReadsPrefsOnlyOnFirstUse() {
        var opened = 0
        val store = ActionSettingsStore { opened++; ctx.getSharedPreferences("t_lazy", Context.MODE_PRIVATE) }
        assertEquals(0, opened)
        store.toggle(ActionType.REMINDER)
        store.settings.value
        assertEquals(1, opened)
    }

    private suspend fun waitFor(cond: suspend () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "condition never met" }
            scheduler.runCurrent()
            Thread.sleep(10)
        }
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
