package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.connectors.actions.ActionParams
import com.rokid.context.connectors.actions.executors.ReminderExecutor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * P7xP8 I2: a proposal stored after its ask/nudge was deleted (Delete all) or redacted (Forget) between the origin's
 * commit and the intake is redacted at once, and its reminder alarm is cancelled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OriginFencedIntakeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val g get() = MemoryGraph.get(ctx)
    private val alarms get() = shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java))

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { g.db.close(); MemoryGraph.resetForTests(null) }

    private val action = ProposedAction.Reminder("Call the clinic", LocalDateTime.now().plusHours(1), "about the results")

    /** Stands in for the action engine: stores an EXECUTED reminder for the origin and arms its alarm. */
    private val engine = ProposalIntake { origin, originId, _, now ->
        val pid = "prop_${origin.name}_$originId"
        val r = ActionParams.Reminder("Call the clinic", now + 3_600_000, "about the results")
        g.proposals.addIgnore(ProposalEntity(pid, origin.name, originId, "REMINDER", ActionParams.encode(r), "EXECUTED", 0, "auto", "alarm:$pid", now))
        ReminderExecutor(ctx).arm(pid, r)
        pid
    }

    private fun fenced() = OriginFencedIntake(ctx, g, engine)

    @Test fun aProposalWhoseAskWasDeletedMeanwhileIsRedactedAndDisarmed() = runBlocking {
        val pid = fenced().onProposed(ProposalOrigin.ASK, "ask_gone", action, System.currentTimeMillis())!!
        assertEquals(ForgetService.REDACTED_PARAMS, g.proposals.byId(pid)!!.paramsJson)
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test fun aProposalWhoseAskWasRedactedMeanwhileIsRedactedAndDisarmed() = runBlocking {
        g.asks.add(AskEntity("ask_r", 1, "q", ForgetService.REDACTED, "[]", null, "phone", true))
        val pid = fenced().onProposed(ProposalOrigin.ASK, "ask_r", action, System.currentTimeMillis())!!
        assertEquals(ForgetService.REDACTED_PARAMS, g.proposals.byId(pid)!!.paramsJson)
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test fun aProposalWhoseNudgeIsGoneIsRedacted() = runBlocking {
        val pid = fenced().onProposed(ProposalOrigin.NUDGE, "nudge_gone", action, System.currentTimeMillis())!!
        assertEquals(ForgetService.REDACTED_PARAMS, g.proposals.byId(pid)!!.paramsJson)
    }

    @Test fun aLiveAsksProposalIsUntouched() = runBlocking {
        g.asks.add(AskEntity("ask_ok", 1, "q", "a", "[]", null, "phone", false))
        val pid = fenced().onProposed(ProposalOrigin.ASK, "ask_ok", action, System.currentTimeMillis())!!
        assertNotEquals(ForgetService.REDACTED_PARAMS, g.proposals.byId(pid)!!.paramsJson)
        assertEquals(1, alarms.scheduledAlarms.size)
    }
}
