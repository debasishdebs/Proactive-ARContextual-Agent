package com.rokid.context.work.frames.scene

import kotlin.math.min
import kotlin.math.sqrt

/** HEAD_MOVING, WALKING and VEHICLE map to [MOVING]; [UNKNOWN] never closes a cluster. */
enum class MotionClass { STILL, MOVING, UNKNOWN }

data class SalienceTerms(val dwell: Float, val text: Float, val novelty: Float, val watch: Float) {
    /** Sum of the terms, capped at 1.0. */
    val total: Float get() = min(1f, dwell + text + novelty + watch)

    fun json(): String =
        "{\"dwell\":$dwell,\"text\":$text,\"novelty\":$novelty,\"watch\":$watch,\"total\":$total}"
}

/** Open for future optional fields; keep the current shape. */
data class RouteInput(
    val frameId: String,
    val ts: Long,
    val dwell: Boolean,
    val motion: MotionClass,
    val blur: Float?,
    val vector: FloatArray?,
    val textWords: Int?,
)

sealed interface Route {
    /** PENDING: per-photo call. */
    data object FastPath : Route

    /** NEAR_DUP, or CAPTION_QUEUED when the frame is salient. */
    data class NearDup(val of: String, val captionQueue: Boolean) : Route

    /** Join or open a scene cluster. */
    data object Cluster : Route
}

/**
 * [driftStreak] holds the ids of the consecutive most recent members that drifted from the centroid
 * (the caller keeps it: append when [SceneRules.isDrifting], clear otherwise).
 */
data class OpenClusterView(
    val openedAt: Long,
    val lastTs: Long,
    val memberCount: Int,
    val motion: MotionClass,
    val centroid: FloatArray?,
    val driftStreak: List<String>,
)

data class RepCandidate(val frameId: String, val vector: FloatArray, val blur: Float?)

object SceneRules {
    private const val EPS = 1e-6f

    /**
     * Terms for one keyframe. A null [maxCosNovelty] (no image vector) gives novelty 0; with a vector and an empty
     * history the caller passes 0f (fully novel). A null [maxCosWatch] gives no watch term.
     */
    fun salience(dwell: Boolean, textWords: Int?, maxCosNovelty: Float?, maxCosWatch: Float?, t: SceneTuning): SalienceTerms =
        SalienceTerms(
            dwell = if (dwell) t.wDwell else 0f,
            text = if (textWords != null && textWords >= t.textMinWords) t.wText else 0f,
            novelty = if (maxCosNovelty == null) 0f else t.wNovelty * (1f - maxCosNovelty.coerceIn(0f, 1f)),
            watch = if (maxCosWatch != null && maxCosWatch >= t.watchCos) t.wWatch else 0f,
        )

    fun isSalient(s: SalienceTerms, t: SceneTuning): Boolean = s.total >= t.salientAt - EPS

    /** Highest-cosine neighbour at or above the dedupe threshold (vectors are unit length, so cosine = dot). */
    fun nearestDup(v: FloatArray, recent: List<Pair<String, FloatArray>>, t: SceneTuning): Pair<String, Float>? {
        var best: Pair<String, Float>? = null
        for ((id, rv) in recent) {
            val c = dot(v, rv)
            if (c >= t.dedupeCos - EPS && (best == null || c > best.second)) best = id to c
        }
        return best
    }

    /** dwell -> FastPath; non-dwell dup -> NearDup (captionQueue = salient); salient -> FastPath; no vector -> FastPath; else Cluster. */
    fun route(input: RouteInput, salience: SalienceTerms, dup: Pair<String, Float>?, t: SceneTuning): Route = when {
        input.dwell -> Route.FastPath
        dup != null -> Route.NearDup(dup.first, isSalient(salience, t))
        isSalient(salience, t) -> Route.FastPath
        input.vector == null -> Route.FastPath
        else -> Route.Cluster
    }

    sealed interface Close {
        data object No : Close
        data class Yes(val reason: String, val carry: List<String>) : Close
    }

    /** True when [v] is further than driftCos from the centroid (1 - cosine > driftCos). */
    fun isDrifting(centroid: FloatArray?, v: FloatArray?, t: SceneTuning): Boolean =
        centroid != null && v != null && (1f - dot(centroid, v)) > t.driftCos + EPS

    /**
     * Before adding [next] to the open cluster. Reasons: out_of_order, gap, age, size, motion, drift.
     * On drift the carry is the earlier drifting frames (they and [next] start the new cluster).
     */
    fun shouldClose(state: OpenClusterView, next: RouteInput, t: SceneTuning): Close {
        if (next.ts < state.lastTs - t.outOfOrderMs) return Close.Yes("out_of_order", emptyList())
        if (next.ts - state.lastTs > t.gapMs) return Close.Yes("gap", emptyList())
        if (next.ts - state.openedAt >= t.maxAgeMs) return Close.Yes("age", emptyList())
        if (state.memberCount >= t.maxMembers) return Close.Yes("size", emptyList())
        if (state.motion != MotionClass.UNKNOWN && next.motion != MotionClass.UNKNOWN && state.motion != next.motion) {
            return Close.Yes("motion", emptyList())
        }
        if (isDrifting(state.centroid, next.vector, t) && state.driftStreak.size + 1 >= t.driftRun) {
            return Close.Yes("drift", state.driftStreak)
        }
        return Close.No
    }

    /** Timer-driven close with no next frame: "age" | "gap" | null. */
    fun closeByTime(state: OpenClusterView, nowMs: Long, t: SceneTuning): String? = when {
        nowMs - state.openedAt >= t.maxAgeMs -> "age"
        nowMs - state.lastTs > t.gapMs -> "gap"
        else -> null
    }

    /** Up to maxReps distinct ids: medoid (max mean cosine), most different (min cosine to the medoid), sharpest (max blur). */
    fun pickReps(members: List<RepCandidate>, t: SceneTuning): List<String> {
        if (members.isEmpty() || t.maxReps <= 0) return emptyList()
        val medoid = members.maxByOrNull { m ->
            members.sumOf { if (it === m) 0.0 else dot(m.vector, it.vector).toDouble() }
        }!!
        val out = mutableListOf(medoid)
        val rest = members.filter { it !== medoid }.toMutableList()
        if (rest.isNotEmpty() && out.size < t.maxReps) {
            val far = rest.minByOrNull { dot(medoid.vector, it.vector) }!!
            out += far; rest.remove(far)
        }
        if (rest.isNotEmpty() && out.size < t.maxReps) {
            val sharp = rest.filter { it.blur != null }.maxByOrNull { it.blur!! } ?: rest.first()
            out += sharp
        }
        return out.map { it.frameId }.distinct().take(t.maxReps)
    }

    /** 1-2 clusters -> 3 each; 3 clusters -> 2 each; total never above callMaxPhotos. */
    fun photosPerCluster(clusters: Int, t: SceneTuning): Int {
        if (clusters <= 0) return 0
        return min(t.maxReps, t.callMaxPhotos / clusters).coerceAtLeast(1)
    }

    /** Mean, then L2 normalised. */
    fun centroid(vs: List<FloatArray>): FloatArray {
        require(vs.isNotEmpty()) { "centroid of nothing" }
        val out = FloatArray(vs[0].size)
        for (v in vs) for (i in out.indices) out[i] += v[i]
        for (i in out.indices) out[i] /= vs.size
        var n = 0.0
        for (x in out) n += x * x
        val norm = sqrt(n).toFloat()
        if (norm > 0f) for (i in out.indices) out[i] /= norm
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until min(a.size, b.size)) s += a[i] * b[i]
        return s
    }
}
