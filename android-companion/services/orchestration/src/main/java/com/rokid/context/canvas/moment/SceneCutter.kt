package com.rokid.context.canvas.moment

import com.rokid.context.memory.db.SceneRule

/**
 * Every tunable of the Scene boundary rules in one place (scene design section 4: starting values, to be tuned on real traces and photos). [hashDistanceFraction] is the share of the
 * 64 perceptual-hash bits that may differ between neighbouring Moments (a random pair differs in about half of them); [minCosine] is the least image-embedding similarity; [capMs]
 * the longest a Scene may last; [activityStates] the motion states that count as a logged activity (a head-moving blip or an unknown state is not an activity).
 */
data class SceneConfig(
    val hashDistanceFraction: Double = 0.25,
    val minCosine: Double = 0.75,
    val capMs: Long = 5 * 60_000L,
    val activityStates: Set<String> = setOf("STILL", "WALKING", "VEHICLE"),
) {
    /** The most hash bits that may differ without a cut (16 of 64 by default). */
    val maxHashBits: Int get() = Math.floor(HASH_BITS * hashDistanceFraction + 1e-9).toInt()

    companion object {
        const val HASH_BITS = 64
        const val HOUR_MS = 3_600_000L
        const val SECOND_MS = 1000L
    }
}

/** What the cutter needs to know about one stored Moment (one phone-clock second). Hashes and embeddings are those of its first and last photo; null = not available. */
class SceneMoment(
    val id: String, val startMs: Long, val activity: String?,
    val hashFirst: Long?, val hashLast: Long?, val embFirst: FloatArray?, val embLast: FloatArray?, val contentHash: String,
)

/** A Moment as a Scene member: the reference and the revision of what it was when the Scene was cut. */
data class SceneMember(val momentId: String, val startMs: Long, val contentHash: String)

/** One planned Scene. [sceneId] is `S` plus the id of its first Moment, so the same input always gives the same ids. */
data class ScenePlan(
    val sceneId: String, val hourStartMs: Long, val startMs: Long, val endMs: Long, val rule: String, val activity: String?, val members: List<SceneMember>,
)

/**
 * The Scene boundary rules, pure and deterministic (no database, no clock, no randomness; the input order does not matter). Moments are walked oldest first and a Scene is cut, in
 * this order of precedence, when: (1) the next Moment lies in another clock hour ([SceneRule.HOUR_END]); (2) both Moments logged an activity and it changed
 * ([SceneRule.ACTIVITY_CHANGE]; frame similarity is not used then), or otherwise the frames changed: the perceptual-hash distance between the last photo of one and the first photo of
 * the next exceeds [SceneConfig.maxHashBits], or, only when both have an embedding of the same size, the cosine similarity is below [SceneConfig.minCosine]
 * ([SceneRule.FRAME_CHANGE]); (3) the Scene would last [SceneConfig.capMs] or more ([SceneRule.CAP_5_MIN]). The Scene records the rule that ended it; the last Scene of an hour ends by
 * [SceneRule.HOUR_END].
 */
class SceneCutter(private val config: SceneConfig = SceneConfig()) {
    fun cut(moments: Collection<SceneMoment>): List<ScenePlan> {
        val sorted = moments.sortedWith(compareBy({ it.startMs }, { it.id }))
        val out = ArrayList<ScenePlan>()
        var cur = ArrayList<SceneMoment>()
        for (m in sorted) {
            val rule = if (cur.isEmpty()) null else cutRule(cur.first(), cur.last(), m)
            if (rule != null) { out += plan(cur, rule); cur = ArrayList() }
            cur += m
        }
        if (cur.isNotEmpty()) out += plan(cur, SceneRule.HOUR_END)
        return out
    }

    private fun hourOf(ms: Long) = Math.floorDiv(ms, SceneConfig.HOUR_MS) * SceneConfig.HOUR_MS

    private fun cutRule(first: SceneMoment, prev: SceneMoment, next: SceneMoment): String? {
        if (hourOf(next.startMs) != hourOf(first.startMs)) return SceneRule.HOUR_END
        val a = prev.activity; val b = next.activity
        if (a != null && b != null) { if (a != b) return SceneRule.ACTIVITY_CHANGE }
        else if (frameChanged(prev, next)) return SceneRule.FRAME_CHANGE
        if (next.startMs - first.startMs >= config.capMs) return SceneRule.CAP_5_MIN
        return null
    }

    private fun frameChanged(prev: SceneMoment, next: SceneMoment): Boolean {
        val h1 = prev.hashLast; val h2 = next.hashFirst
        if (h1 != null && h2 != null && java.lang.Long.bitCount(h1 xor h2) > config.maxHashBits) return true
        val c = cosine(prev.embLast, next.embFirst)
        return c != null && c < config.minCosine
    }

    /** Null when either vector is missing, they differ in size or one has no length (nothing to compare). */
    private fun cosine(a: FloatArray?, b: FloatArray?): Double? {
        if (a == null || b == null || a.size != b.size || a.isEmpty()) return null
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i].toDouble(); na += a[i] * a[i].toDouble(); nb += b[i] * b[i].toDouble() }
        if (na == 0.0 || nb == 0.0) return null
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    private fun plan(ms: List<SceneMoment>, rule: String): ScenePlan {
        val first = ms.first(); val last = ms.last()
        val activity = ms.mapNotNull { it.activity }.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.key
        return ScenePlan(
            "S" + first.id, hourOf(first.startMs), first.startMs, last.startMs + SceneConfig.SECOND_MS - 1, rule, activity,
            ms.map { SceneMember(it.id, it.startMs, it.contentHash) },
        )
    }
}
