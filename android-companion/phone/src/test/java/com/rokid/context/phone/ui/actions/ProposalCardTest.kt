package com.rokid.context.phone.ui.actions

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.ActionCopy
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.Resolve
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

class FakeCardSource(
    ui: ProposalUi?, private val answer: Resolve = Resolve.Won(ProposalState.EXECUTING), private val undoOk: Boolean = true, private val undoMs: Long = 0,
) : ProposalCardSource {
    val flow = MutableStateFlow(ui)
    val answers = mutableListOf<Boolean>()
    override fun observe(originId: String): Flow<ProposalUi?> = flow
    override suspend fun respond(proposalId: String, yes: Boolean): Resolve { answers += yes; return answer }
    override suspend fun undo(proposalId: String) = undoOk
    override fun undoRemainingMs(proposalId: String): Long = undoMs
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ProposalCardTest {
    @get:Rule val compose = createComposeRule()
    private fun ui(state: ProposalState, canAnswer: Boolean = false, canUndo: Boolean = false, failure: String? = null) =
        ProposalUi("p1", ActionType.REMINDER, "Call mom \u00b7 Tue 18:00", state, canAnswer, canUndo, failure)

    @Test fun suppressedShowsEnableLinkNoButtons() {
        var opened = 0
        compose.setContent { PcTheme { ProposalCard("a1", source = FakeCardSource(ui(ProposalState.SUPPRESSED)), onEnable = { opened++ }) } }
        compose.onNodeWithText("Suggested: Call mom \u00b7 Tue 18:00").assertIsDisplayed()
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onNodeWithText("No thanks").assertDoesNotExist()
        compose.onNodeWithText("Enable in Settings").performClick()
        assertEquals(1, opened)
    }

    @Test fun pendingAnswersThroughTheEngine() {
        val src = FakeCardSource(ui(ProposalState.PENDING, canAnswer = true))
        compose.setContent { PcTheme { ProposalCard("a1", source = src) } }
        compose.onNodeWithText("No thanks").performClick()
        compose.waitUntil(3_000) { src.answers.isNotEmpty() }
        assertEquals(listOf(false), src.answers)
    }

    @Test fun lateTapShowsAlreadyAdded() {
        val src = FakeCardSource(ui(ProposalState.PENDING, canAnswer = true), answer = Resolve.Lost(ProposalState.EXECUTED))
        compose.setContent { PcTheme { ProposalCard("a1", source = src) } }
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Already added")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun terminalStatesReadAsPlainWords() {
        val src = FakeCardSource(ui(ProposalState.FAILED, failure = "Calendar permission needed"))
        compose.setContent { PcTheme { ProposalCard("a1", source = src) } }
        compose.onNodeWithText("Calendar permission needed").assertIsDisplayed()
        src.flow.value = ProposalUi("p1", ActionType.REMINDER, "x", ProposalState.DECLINED, false, false, null, undone = true)
        compose.onNodeWithText("Undone").assertIsDisplayed()
        src.flow.value = ui(ProposalState.EXPIRED)
        compose.onNodeWithText("Expired").assertIsDisplayed()
    }

    /** Final review I2: an added reminder that can't alert says so, with a way to fix it (no raw error text). */
    @Test fun addedWithNotificationsOffOffersTurnOn() {
        var fixed = 0
        val src = FakeCardSource(ui(ProposalState.EXECUTED).copy(warning = ActionCopy.warningTitle("NOTIFICATIONS_OFF")))
        compose.setContent { PcTheme { ProposalCard("a1", source = src, onFixNotifications = { fixed++ }) } }
        compose.onNodeWithText("Added, but notifications are off").assertIsDisplayed()
        compose.onNodeWithText("Added").assertDoesNotExist()
        compose.onNodeWithText("Turn on").performClick()
        assertEquals(1, fixed)
    }

    @Test fun warningIsBuiltOnlyForAnExecutedRow() {
        val row = ProposalEntity(
            "p1", "ASK", "a1", ActionType.REMINDER.name, "{}", ProposalState.EXECUTED.name, 0L, resolvedBy = "phone", createdRef = "alarm:p1", createdAt = 0L,
        )
        val zone = java.time.ZoneId.of("UTC")
        assertEquals("Added, but notifications are off", ActionsUi.build(row, null, 0, zone, warningCode = "NOTIFICATIONS_OFF")!!.warning)
        assertNull(ActionsUi.build(row, null, 0, zone)!!.warning)
        assertNull(ActionsUi.build(row.copy(state = ProposalState.DECLINED.name), null, 0, zone, warningCode = "NOTIFICATIONS_OFF")!!.warning)
    }

    @Test fun failedUndoSaysSo() {
        val src = FakeCardSource(ui(ProposalState.EXECUTED, canUndo = true), undoOk = false, undoMs = 60_000)
        compose.setContent { PcTheme { ProposalCard("a1", source = src) } }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(3_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Couldn't undo")).fetchSemanticsNodes().isNotEmpty() }
    }
}
