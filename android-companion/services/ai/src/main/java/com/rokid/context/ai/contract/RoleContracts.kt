package com.rokid.context.ai.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime

data class SemanticContext(val inputIds: Set<String>, val now: LocalDateTime)

/** Contract decoders + semantic checks (spec §4.4.1), used in production (RoleRunner) and the test bed alike. */
object RoleContracts {
    private val PRIORITY = mapOf("low" to 0, "normal" to 1, "high" to 2)

    fun decode(key: PromptKey, value: JsonElement): Result<ContractOutput> = runCatching {
        val o = value.jsonObject
        when (key) {
            PromptKey.SYSTEM -> throw IllegalArgumentException("SYSTEM has no output")
            PromptKey.VISION_EXTRACT, PromptKey.EXPLICIT_REMEMBER -> ExtractOutput(
                o.getValue("facts").jsonArray.map { f ->
                    val fo = f.jsonObject
                    Fact(str(fo, "text"), str(fo, "type"), fo.getValue("confidence").let { (it as JsonPrimitive).double }, strings(fo, "tags"))
                },
                extras(o, "facts"),
            )
            PromptKey.SUMMARIZE -> SummaryOutput(
                str(o, "summary"), strings(o, "events"), strings(o, "entities"), (o.getValue("confidence") as JsonPrimitive).double,
                str(o, "candidate_memory_type"), extras(o, "summary", "events", "entities", "confidence", "candidate_memory_type"),
            )
            PromptKey.CRITIC -> CriticOutput(
                when (str(o, "verdict")) { "pass" -> true; "fail" -> false; else -> throw IllegalArgumentException("verdict must be pass or fail") },
                strings(o, "reasons"), extras(o, "verdict", "reasons"),
            )
            PromptKey.ANSWER -> AnswerOutput(str(o, "answer"), strings(o, "sources"), action(o), extras(o, "answer", "sources", "proposedAction"))
            PromptKey.NUDGE -> NudgeOutput(
                (o.getValue("shouldNudge") as JsonPrimitive).boolean, str(o, "text"), str(o, "why"), strings(o, "sourceIds"),
                PRIORITY[str(o, "priority")] ?: throw IllegalArgumentException("priority must be low, normal or high"),
                action(o), extras(o, "shouldNudge", "text", "why", "sourceIds", "priority", "proposedAction"),
            )
        }
    }

    fun semanticProblems(output: ContractOutput, ctx: SemanticContext): List<String> = buildList {
        when (output) {
            is ExtractOutput -> output.facts.forEachIndexed { i, f ->
                if (f.text.isBlank()) add("facts[$i].text is empty")
                if (f.confidence !in 0.0..1.0) add("facts[$i].confidence must be 0–1")
            }
            is SummaryOutput -> if (output.confidence !in 0.0..1.0) add("confidence must be 0–1")
            is CriticOutput -> Unit
            is AnswerOutput -> { sources(output.sources, ctx)?.let { add(it) }; addAll(action(output.proposedAction, ctx)) }
            is NudgeOutput -> {
                sources(output.sourceIds, ctx)?.let { add(it) }
                if (output.shouldNudge && output.text.isBlank()) add("text is empty but shouldNudge is true")
                addAll(action(output.proposedAction, ctx))
            }
        }
    }

    private fun sources(ids: List<String>, ctx: SemanticContext): String? =
        ids.filterNot { it in ctx.inputIds }.takeIf { it.isNotEmpty() }?.let { "sources not in the given memories: ${it.take(3).joinToString(", ")}" }

    private fun action(a: ProposedAction?, ctx: SemanticContext): List<String> = when (a) {
        null -> emptyList()
        // The bounds the schema states are checked again after decoding, so a loosened schema can't pass them (Phase 5 T5 carry).
        is ProposedAction.ShoppingAdd -> buildList {
            if (a.items.isEmpty() || a.items.size > 10) add("proposedAction.items must hold 1\u201310 items")
            a.items.forEachIndexed { i, l ->
                if (l.name.isBlank()) add("proposedAction.items[$i].name is empty")
                l.qty?.let { q -> if (!(q > 0) || !q.isFinite()) add("proposedAction.items[$i].qty must be more than 0") }
            }
        }
        is ProposedAction.Reminder -> listOfNotNull(
            "proposedAction.title is empty".takeIf { a.title.isBlank() },
            "proposedAction.dueAt must be in the future".takeUnless { a.dueAt.isAfter(ctx.now) },
        )
        is ProposedAction.CalendarEvent -> listOfNotNull(
            "proposedAction.startAt must be in the future".takeUnless { a.startAt.isAfter(ctx.now) },
            "proposedAction.endAt must be after startAt".takeUnless { a.endAt.isAfter(a.startAt) },
        )
    }

    private fun action(o: JsonObject): ProposedAction? {
        val a = o["proposedAction"]?.takeUnless { it == JsonNull }?.jsonObject ?: return null
        return when (str(a, "type")) {
            "REMINDER" -> ProposedAction.Reminder(str(a, "title"), LocalDateTime.parse(str(a, "dueAt")), opt(a, "note"))
            "SHOPPING_ADD" -> ProposedAction.ShoppingAdd(a.getValue("items").jsonArray.map { i ->
                val io = i.jsonObject
                ShoppingLine(str(io, "name"), (io["qty"] as? JsonPrimitive)?.takeUnless { it == JsonNull }?.double, opt(io, "unit"))
            })
            "CALENDAR_EVENT" -> {
                val start = LocalDateTime.parse(str(a, "startAt"))
                ProposedAction.CalendarEvent(str(a, "title"), start, opt(a, "endAt")?.let(LocalDateTime::parse) ?: start.plusMinutes(30), opt(a, "location"))
            }
            else -> throw IllegalArgumentException("Unknown action type")
        }
    }

    private fun str(o: JsonObject, k: String): String = (o.getValue(k) as JsonPrimitive).also { require(it.isString) { "$k must be text" } }.content
    private fun opt(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it != JsonNull && it.isString }?.content
    private fun strings(o: JsonObject, k: String): List<String> = (o[k] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
    private fun extras(o: JsonObject, vararg known: String): JsonObject = JsonObject(o.filterKeys { it !in known })
}
