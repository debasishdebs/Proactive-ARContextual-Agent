package com.rokid.context.orchestration.proactive

import org.json.JSONObject

/** What the user did with a suggestion (design 2.6). Counters only: no text, no learned model. */
enum class FeedbackAction(val token: String) { DISMISS("dismiss"), SNOOZE("snooze"), NEVER("never"), UP("up"), DOWN("down") }

/** Per key: the last dismissal, a snooze, and the latest thumb ([vote] is -1, 0 or +1). Bounded plain numbers. */
data class KeyFeedback(val dismissCount: Int = 0, val lastDismissMs: Long = 0L, val snoozeUntilMs: Long = 0L, val vote: Int = 0, val touchedMs: Long = 0L)
data class FamilyFeedback(val up: Int = 0, val down: Int = 0)

/** The whole learning state: bounded by [MAX_KEYS] keys and [FamilyFeedback] counters of at most [MAX_FAMILY_COUNT]. Reset by "Reset learning" and Delete all. */
data class FeedbackState(val keys: Map<String, KeyFeedback> = emptyMap(), val families: Map<ProactiveFamily, FamilyFeedback> = emptyMap()) {
    /** The state after [action] on [key]; pure. NEVER only counts here (the never list itself lives in the store). */
    fun after(action: FeedbackAction, key: String, family: ProactiveFamily, nowMs: Long): FeedbackState {
        val k = keys[key] ?: KeyFeedback()
        var f = families[family] ?: FamilyFeedback()
        val nk = when (action) {
            FeedbackAction.DISMISS -> k.copy(dismissCount = (k.dismissCount + 1).coerceAtMost(MAX_FAMILY_COUNT), lastDismissMs = nowMs)
            FeedbackAction.SNOOZE -> k.copy(snoozeUntilMs = nowMs + FeedbackMath.SNOOZE_MS)
            FeedbackAction.NEVER -> k
            FeedbackAction.UP -> if (k.vote == 1) k else { if (k.vote == -1) f = f.copy(down = (f.down - 1).coerceAtLeast(0)); f = f.copy(up = (f.up + 1).coerceAtMost(MAX_FAMILY_COUNT)); k.copy(vote = 1) }
            FeedbackAction.DOWN -> if (k.vote == -1) k else { if (k.vote == 1) f = f.copy(up = (f.up - 1).coerceAtLeast(0)); f = f.copy(down = (f.down + 1).coerceAtMost(MAX_FAMILY_COUNT)); k.copy(vote = -1) }
        }.copy(touchedMs = nowMs)
        val nks = LinkedHashMap(keys); nks.remove(key); nks[key] = nk
        while (nks.size > MAX_KEYS) nks.remove(nks.entries.minByOrNull { it.value.touchedMs }!!.key)
        return FeedbackState(nks, families + (family to f))
    }

    fun toJson(): String = JSONObject()
        .put("k", JSONObject().also { o -> keys.forEach { (key, v) -> o.put(key, JSONObject().put("d", v.dismissCount).put("ld", v.lastDismissMs).put("su", v.snoozeUntilMs).put("v", v.vote).put("t", v.touchedMs)) } })
        .put("f", JSONObject().also { o -> families.forEach { (fam, v) -> o.put(fam.token, JSONObject().put("u", v.up).put("d", v.down)) } }).toString()

    companion object {
        const val MAX_KEYS = 200
        const val MAX_FAMILY_COUNT = 20
        val EMPTY = FeedbackState()

        fun fromJson(s: String?): FeedbackState = try {
            val o = JSONObject(s ?: return EMPTY)
            val ko = o.optJSONObject("k") ?: JSONObject(); val fo = o.optJSONObject("f") ?: JSONObject()
            val keys = LinkedHashMap<String, KeyFeedback>()
            for (key in ko.keys()) ko.getJSONObject(key).let { keys[key] = KeyFeedback(it.optInt("d"), it.optLong("ld"), it.optLong("su"), it.optInt("v").coerceIn(-1, 1), it.optLong("t")) }
            val fams = ProactiveFamily.entries.mapNotNull { fam -> fo.optJSONObject(fam.token)?.let { fam to FamilyFeedback(it.optInt("u").coerceIn(0, MAX_FAMILY_COUNT), it.optInt("d").coerceIn(0, MAX_FAMILY_COUNT)) } }.toMap()
            FeedbackState(keys, fams)
        } catch (e: Exception) { EMPTY }
    }
}

/**
 * How feedback changes ranking (design 2.6), bounded: a dismissal multiplies the key by 0.7 and recovers linearly over 14 days; a thumbs down ×0.4 (key) and ×0.9 (family); a thumbs up ×1.2 and ×1.05.
 * The family multiplier stays in [0.25, 1.5]. A snooze, and 24 h after a dismissal, BLOCK the key (cooldown) instead of ranking it. Never lives in the store's never list.
 */
object FeedbackMath {
    const val DISMISS_FACTOR = 0.7
    const val RECOVER_MS = 14 * 24 * 3_600_000L
    const val DOWN_KEY = 0.4
    const val UP_KEY = 1.2
    const val DOWN_FAMILY = 0.9
    const val UP_FAMILY = 1.05
    const val FAMILY_MIN = 0.25
    const val FAMILY_MAX = 1.5
    const val SNOOZE_MS = 24 * 3_600_000L
    const val DISMISS_COOLDOWN_MS = 24 * 3_600_000L

    fun keyMultiplier(k: KeyFeedback?, nowMs: Long): Double {
        if (k == null) return 1.0
        val dismiss = if (k.lastDismissMs <= 0L) 1.0 else DISMISS_FACTOR + (1.0 - DISMISS_FACTOR) * ((nowMs - k.lastDismissMs).coerceAtLeast(0L).toDouble() / RECOVER_MS).coerceIn(0.0, 1.0)
        val vote = when (k.vote) { 1 -> UP_KEY; -1 -> DOWN_KEY; else -> 1.0 }
        return dismiss * vote
    }

    fun familyMultiplier(f: FamilyFeedback?): Double = if (f == null) 1.0 else (Math.pow(DOWN_FAMILY, f.down.toDouble()) * Math.pow(UP_FAMILY, f.up.toDouble())).coerceIn(FAMILY_MIN, FAMILY_MAX)

    fun multiplier(key: String, family: ProactiveFamily, s: FeedbackState, nowMs: Long): Double = keyMultiplier(s.keys[key], nowMs) * familyMultiplier(s.families[family])

    /** True while the key is snoozed or was dismissed less than [DISMISS_COOLDOWN_MS] ago. */
    fun blocked(key: String, s: FeedbackState, nowMs: Long): Boolean {
        val k = s.keys[key] ?: return false
        return nowMs < k.snoozeUntilMs || (k.lastDismissMs > 0L && nowMs - k.lastDismissMs in 0 until DISMISS_COOLDOWN_MS)
    }
}
