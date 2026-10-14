package com.rokid.context.ai.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RoleContractsTest {
    private fun j(t: String) = Json.parseToJsonElement(t)
    private val now = LocalDateTime.of(2026, 10, 12, 10, 0)
    private val ctx = SemanticContext(setOf("m1", "m2"), now)

    @Test fun answerWithEachActionTypeDecodes() {
        val r = RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"ok","sources":["m1"],"proposedAction":{"type":"REMINDER","title":"Call Priya","dueAt":"2026-10-12T18:30","note":"re: keys"},"mood":"calm"}""")).getOrThrow() as AnswerOutput
        assertEquals(ProposedAction.Reminder("Call Priya", LocalDateTime.of(2026, 10, 12, 18, 30), "re: keys"), r.proposedAction)
        assertEquals(JsonPrimitive("calm"), r.extras["mood"])
        val c = RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"ok","sources":[],"proposedAction":{"type":"CALENDAR_EVENT","title":"Dentist","startAt":"2026-10-13T09:00"}}""")).getOrThrow() as AnswerOutput
        assertEquals(LocalDateTime.of(2026, 10, 13, 9, 30), (c.proposedAction as ProposedAction.CalendarEvent).endAt)
        val s = RoleContracts.decode(PromptKey.NUDGE, j("""{"shouldNudge":true,"text":"Milk","why":"empty","sourceIds":["m2"],"priority":"low","proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk","qty":2,"unit":"l"}]}}""")).getOrThrow() as NudgeOutput
        assertEquals(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", 2.0, "l"))), s.proposedAction)
        assertEquals(0, s.priority)
    }

    /** "note that…" (user notes): NOTE_ADD carries the note text; a blank or over-long text is a semantic problem. */
    @Test fun noteAddDecodesAndIsChecked() {
        val r = RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"Noted","sources":[],"proposedAction":{"type":"NOTE_ADD","text":"the car is on level 3"}}""")).getOrThrow() as AnswerOutput
        assertEquals(ProposedAction.NoteAdd("the car is on level 3"), r.proposedAction)
        assertEquals(emptyList<String>(), RoleContracts.semanticProblems(r, ctx))
        val blank = RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"Noted","sources":[],"proposedAction":{"type":"NOTE_ADD","text":"  "}}""")).getOrThrow()
        assertEquals(listOf("proposedAction.text is empty"), RoleContracts.semanticProblems(blank, ctx))
        assertTrue(RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"Noted","sources":[],"proposedAction":{"type":"NOTE_ADD"}}""")).isFailure)
    }

    @Test fun decodeFailsOnUnknownActionAndBadDates() {
        assertTrue(RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"ok","sources":[],"proposedAction":{"type":"EMAIL"}}""")).isFailure)
        assertTrue(RoleContracts.decode(PromptKey.ANSWER, j("""{"answer":"ok","sources":[],"proposedAction":{"type":"REMINDER","title":"t","dueAt":"soon"}}""")).isFailure)
        assertTrue(RoleContracts.decode(PromptKey.CRITIC, j("""{"verdict":"maybe","reasons":[]}""")).isFailure)
    }

    @Test fun semanticChecks() {
        fun answer(t: String) = RoleContracts.decode(PromptKey.ANSWER, j(t)).getOrThrow()
        assertEquals(emptyList<String>(), RoleContracts.semanticProblems(answer("""{"answer":"ok","sources":["m1"]}"""), ctx))
        assertEquals(listOf("sources not in the given memories: x9"), RoleContracts.semanticProblems(answer("""{"answer":"ok","sources":["m1","x9"]}"""), ctx))
        assertEquals(listOf("proposedAction.dueAt must be in the future"),
            RoleContracts.semanticProblems(answer("""{"answer":"ok","sources":[],"proposedAction":{"type":"REMINDER","title":"t","dueAt":"2026-10-12T09:00"}}"""), ctx))
        assertEquals(listOf("proposedAction.endAt must be after startAt"),
            RoleContracts.semanticProblems(answer("""{"answer":"ok","sources":[],"proposedAction":{"type":"CALENDAR_EVENT","title":"t","startAt":"2026-10-13T09:00","endAt":"2026-10-13T08:00"}}"""), ctx))
        val nudge = RoleContracts.decode(PromptKey.NUDGE, j("""{"shouldNudge":true,"text":" ","why":"w","sourceIds":[],"priority":"normal"}""")).getOrThrow()
        assertEquals(listOf("text is empty but shouldNudge is true"), RoleContracts.semanticProblems(nudge, ctx))
        val facts = RoleContracts.decode(PromptKey.VISION_EXTRACT, j("""{"facts":[{"text":"Milk","type":"object","confidence":1.4,"tags":[]}]}""")).getOrThrow()
        assertEquals(listOf("facts[0].confidence must be 0–1"), RoleContracts.semanticProblems(facts, ctx))
    }
}
