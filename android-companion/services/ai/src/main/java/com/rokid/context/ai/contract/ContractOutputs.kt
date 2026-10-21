package com.rokid.context.ai.contract

import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime

/** spec §4.4 `ProposedAction`, decoded from ANSWER or NUDGE. The phone assigns the proposalId (Phase 7). */
sealed interface ProposedAction {
    data class Reminder(val title: String, val dueAt: LocalDateTime, val note: String?) : ProposedAction
    data class ShoppingAdd(val items: List<ShoppingLine>) : ProposedAction
    /** A user note ("note that…"): the text without the trigger phrase. */
    data class NoteAdd(val text: String) : ProposedAction
    data class CalendarEvent(val title: String, val startAt: LocalDateTime, val endAt: LocalDateTime, val location: String?) : ProposedAction
}

data class ShoppingLine(val name: String, val qty: Double?, val unit: String?)

/** A role's contract type (spec §4.4.1). [extras] = optional properties a refined schema added; the pipeline ignores them. */
sealed interface ContractOutput { val extras: JsonObject }

/** [sourceFrameIds]: the photos a VISION_EXTRACT fact is visible in (empty for EXPLICIT_REMEMBER). */
data class Fact(val text: String, val type: String, val confidence: Double, val tags: List<String>, val sourceFrameIds: List<String> = emptyList())
data class FrameCaption(val frameId: String, val caption: String)
/** [provenance]: a VISION_EXTRACT output, whose ids and captions the validator checks against the photos sent. */
data class ExtractOutput(
    val facts: List<Fact>, override val extras: JsonObject = JsonObject(emptyMap()),
    val captions: List<FrameCaption> = emptyList(), val provenance: Boolean = false,
) : ContractOutput
/** An INTENT_CLASSIFY result: [label] (unknown names are OTHER) and [confidence] clamped to 0..1. */
data class IntentOutput(val label: com.rokid.context.core.model.context.IntentLabel, val confidence: Float, override val extras: JsonObject = JsonObject(emptyMap())) : ContractOutput
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
