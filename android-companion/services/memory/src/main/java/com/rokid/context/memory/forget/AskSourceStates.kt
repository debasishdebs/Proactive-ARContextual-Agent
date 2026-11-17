package com.rokid.context.memory.forget

import com.rokid.context.memory.db.AskEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON source states of an answer (`ask.sourceStates`, Room v17; Forget lifecycle M5, rulings 2026-11-17). IDS AND FIXED TOKENS ONLY: never a question, an answer or a memory text.
 *
 *     {"v":1,"state":"LIVE","cited":["rokid_mem_1","frame:f1"],"history":["nudge:n7"]}
 *
 * - [cited] are the sources the answer named (design A1: only these are its dependencies with `forgetV2` on); every other input it merely retrieved is NOT a dependency then.
 * - [history] are the earlier answers (`ask:<id>`) and nudges (`nudge:<id>`) whose text was in its prompt (design A2: the item itself is the dependency, so forgetting it reaches this answer).
 * - [state] is LIVE, [SOURCE_FORGOTTEN] (a Forget removed a source: the answer text is erased, the question kept, the row offers "Ask again") or [REASKED] (the user asked again; [reaskedAs] is the new row).
 * A null or unreadable value means "written before v17": the legacy matching applies (the cited array of `sourcesJson`).
 */
object AskSourceStates {
    const val VERSION = 1
    const val LIVE = "LIVE"
    const val SOURCE_FORGOTTEN = "SOURCE_FORGOTTEN"
    const val REASKED = "REASKED"
    /** `ask.source` of a developer eval ask: never takes the v2 rules (it is not listed and keeps v1 behaviour). */
    const val EVAL_SOURCE = "canvas_eval"
    const val ASK_PREFIX = "ask:"
    const val NUDGE_PREFIX = "nudge:"

    data class States(val state: String, val cited: List<String>, val history: List<String>, val reaskedAs: String? = null)

    fun live(cited: Collection<String>, history: Collection<String>): String = JSONObject()
        .put("v", VERSION).put("state", LIVE).put("cited", JSONArray(cited.distinct())).put("history", JSONArray(history.distinct())).toString()

    /** After a Forget removed a source: no ids are kept (the answer is erased in the same transaction). */
    fun forgotten(): String = JSONObject().put("v", VERSION).put("state", SOURCE_FORGOTTEN).toString()

    fun reasked(newAskId: String): String = JSONObject().put("v", VERSION).put("state", REASKED).put("reaskedAs", newAskId).toString()

    fun parse(json: String?): States? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            if (o.optInt("v", -1) != VERSION) return null
            fun ids(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } } }.orEmpty()
            val state = o.optString("state", "").takeIf { it == LIVE || it == SOURCE_FORGOTTEN || it == REASKED } ?: return null
            States(state, ids("cited"), ids("history"), o.optString("reaskedAs", "").takeIf { it.isNotEmpty() })
        }.getOrNull()
    }

    /** The row is an answer whose text a Forget erased because a source was forgotten (the UI state "Answered from a forgotten fact"). */
    fun isForgotten(ask: AskEntity): Boolean = ask.redacted && parse(ask.sourceStates)?.state.let { it == SOURCE_FORGOTTEN || it == REASKED }

    /** The user already asked this question again: no button, "Asked again". */
    fun isReasked(ask: AskEntity): Boolean = ask.redacted && parse(ask.sourceStates)?.state == REASKED

    /**
     * What forgetting makes this answer rebuilt, with `forgetV2` on (design A1 and A2): its cited ids and its history items. A row with no states (written before v17) uses the CITED array of its
     * `sourcesJson`, never its `dependsOn` (which holds every retrieved input). A developer eval ask keeps the v1 rule (all ids).
     */
    fun dependenciesV2(ask: AskEntity): Set<String> {
        if (ask.source == EVAL_SOURCE) return ForgetService.parseIds(ask.sourcesJson)
        val s = parse(ask.sourceStates) ?: return citedArray(ask.sourcesJson)
        return (s.cited + s.history).toSet()
    }

    private fun citedArray(sourcesJson: String): Set<String> = runCatching {
        val a = JSONArray(sourcesJson)
        (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }.toSet()
    }.getOrDefault(emptySet())
}
