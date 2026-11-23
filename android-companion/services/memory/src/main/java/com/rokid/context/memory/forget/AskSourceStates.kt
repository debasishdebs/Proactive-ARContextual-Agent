package com.rokid.context.memory.forget

import com.rokid.context.memory.db.AskEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * The JSON source states of an answer (`ask.sourceStates`, Room v17; Forget lifecycle M5, rulings 2026-11-17). IDS AND FIXED TOKENS ONLY: never a question, an answer or a memory text.
 *
 *     {"v":1,"state":"LIVE","cited":["rokid_mem_1","frame:f1"],"history":["nudge:n7"]}
 *
 * - [cited] are the sources the answer named. With `forgetV2` on EVERY supplied input is still a dependency (ruling F4): `sourcesJson` also lists the uncited ones (`dependsOn`).
 * - [history] are the earlier answers (`ask:<id>`) and nudges (`nudge:<id>`) whose text was in its prompt (design A2: the item itself is the dependency, so forgetting it reaches this answer).
 * - [state] is LIVE, [SOURCE_FORGOTTEN] (a Forget removed a source: the answer text is erased, the question kept, the row offers "Ask again") or [REASKED] (the user asked again; [reaskedAs] is the new row).
 * A null or unreadable value means "written before v17": the stored `sourcesJson` ids (cited and `dependsOn`) are the dependencies.
 */
object AskSourceStates {
    const val VERSION = 1
    const val LIVE = "LIVE"
    const val SOURCE_FORGOTTEN = "SOURCE_FORGOTTEN"
    const val REASKED = "REASKED"
    /** P3: system expiry removed a short-term source and the answer was KEPT whole. Not a forget: [isForgotten] is false for it and the text is never erased. An older build reads it as an unknown state (null = written before v17). */
    const val SOURCE_EXPIRED = "SOURCE_EXPIRED"
    /** `ask.source` of a developer eval ask: never takes the v2 rules (it is not listed and keeps v1 behaviour). */
    const val EVAL_SOURCE = "canvas_eval"
    const val ASK_PREFIX = "ask:"
    const val NUDGE_PREFIX = "nudge:"

    data class States(val state: String, val cited: List<String>, val history: List<String>, val reaskedAs: String? = null)

    fun live(cited: Collection<String>, history: Collection<String>): String = JSONObject()
        .put("v", VERSION).put("state", LIVE).put("cited", JSONArray(cited.distinct())).put("history", JSONArray(history.distinct())).toString()

    /** After a Forget removed a source: no ids are kept (the answer is erased in the same transaction). */
    fun forgotten(): String = JSONObject().put("v", VERSION).put("state", SOURCE_FORGOTTEN).toString()

    /** The state of a kept answer some of whose short-term sources expired (ids and token only). */
    fun expired(cited: Collection<String>, history: Collection<String>): String = JSONObject()
        .put("v", VERSION).put("state", SOURCE_EXPIRED).put("cited", JSONArray(cited.distinct())).put("history", JSONArray(history.distinct())).toString()

    fun reasked(newAskId: String): String = JSONObject().put("v", VERSION).put("state", REASKED).put("reaskedAs", newAskId).toString()

    fun parse(json: String?): States? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(json)
            if (o.optInt("v", -1) != VERSION) return null
            fun ids(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } } }.orEmpty()
            val state = o.optString("state", "").takeIf { it == LIVE || it == SOURCE_FORGOTTEN || it == REASKED || it == SOURCE_EXPIRED } ?: return null
            States(state, ids("cited"), ids("history"), o.optString("reaskedAs", "").takeIf { it.isNotEmpty() })
        }.getOrNull()
    }

    /** The row is an answer whose text a Forget erased because a source was forgotten (the UI state "Answered from a forgotten fact"). */
    fun isForgotten(ask: AskEntity): Boolean = ask.redacted && parse(ask.sourceStates)?.state.let { it == SOURCE_FORGOTTEN || it == REASKED }

    /** P3: the answer is whole and some of its short-term sources have expired (never a forget; `redacted` is false). */
    fun isExpired(ask: AskEntity): Boolean = !ask.redacted && parse(ask.sourceStates)?.state == SOURCE_EXPIRED

    /** The user already asked this question again: no button, "Asked again". */
    fun isReasked(ask: AskEntity): Boolean = ask.redacted && parse(ask.sourceStates)?.state == REASKED

    /**
     * What forgetting makes this answer rebuilt, with `forgetV2` on (design A1 and A2; proxy ruling F4, Codex M5 r1 P1): CONSERVATIVE, ALL supplied context. The ids stored in `sourcesJson` (the cited array AND the
     * trailing `dependsOn` object: every retrieved input, nudge history included, so a migrated v16 answer keeps its protection), plus the state's cited ids and history items. Never narrowed to the citations:
     * an uncited input can still have shaped the text.
     */
    fun dependenciesV2(ask: AskEntity): Set<String> {
        val stored = ForgetService.parseIds(ask.sourcesJson)
        if (ask.source == EVAL_SOURCE) return stored
        val s = parse(ask.sourceStates) ?: return stored
        return stored + s.cited + s.history
    }
}
