package com.rokid.context.ai.contract

import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime

/** spec §4.4 `ProposedAction`, decoded from ANSWER or NUDGE. The phone assigns the proposalId (Phase 7). */
sealed interface ProposedAction {
    data class Reminder(val title: String, val dueAt: LocalDateTime, val note: String?) : ProposedAction
    data class ShoppingAdd(val items: List<ShoppingLine>) : ProposedAction
    data class CalendarEvent(val title: String, val startAt: LocalDateTime, val endAt: LocalDateTime, val location: String?) : ProposedAction
}

data class ShoppingLine(val name: String, val qty: Double?, val unit: String?)

/** A role's contract type (spec §4.4.1). [extras] = optional properties a refined schema added; the pipeline ignores them. */
sealed interface ContractOutput { val extras: JsonObject }

data class Fact(val text: String, val type: String, val confidence: Double, val tags: List<String>)
data class ExtractOutput(val facts: List<Fact>, override val extras: JsonObject = JsonObject(emptyMap())) : ContractOutput
data class SummaryOutput(
    val summary: String, val events: List<String>, val entities: List<String>, val confidence: Double, val candidateMemoryType: String,
    override val extras: JsonObject = JsonObject(emptyMap()),
) : ContractOutput
data class CriticOutput(val pass: Boolean, val reasons: List<String>, override val extras: JsonObject = JsonObject(emptyMap())) : ContractOutput
data class AnswerOutput(val answer: String, val sources: List<String>, val proposedAction: ProposedAction?, override val extras: JsonObject = JsonObject(emptyMap())) : ContractOutput
data class NudgeOutput(
    val shouldNudge: Boolean, val text: String, val why: String, val sourceIds: List<String>, val priority: Int, val proposedAction: ProposedAction?,
    override val extras: JsonObject = JsonObject(emptyMap()),
) : ContractOutput
