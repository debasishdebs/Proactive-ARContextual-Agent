package com.rokid.context.work.frames.place

import com.rokid.context.core.model.vision.CaptionRefused
import com.rokid.context.core.model.vision.OfflineCaptioner
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * The offline place tier (V2b, owner decisions 2026-11-26 rounds 5 and 6): when no accepted place exists and the model path is offline, the photo is captioned ON THE PHONE and the caption is ranked, as text, against the
 * candidate places. At most ONE result, shown as "Maybe <place>", and only when the best score reaches the cut-off. Not gated by the 95 percent target (that only guards the embedding accept step).
 *
 * Pure and testable: the captioner and the text embedder are injected. PRIVACY: a caption is content. It lives in a local of [guess] only: never logged, never persisted, dropped after ranking. The only thing that leaves
 * this class is an [OfflineTierResult] (a place the caller already holds, plus a fixed token made of counts and buckets).
 */
class OfflineTier(
    private val captioner: OfflineCaptioner,
    private val embedder: TextEmbed,
    /** Both Developer flags, the user's switch and the model files: false = nothing runs, nothing is read. Asked first, every time. */
    private val enabled: () -> Boolean,
    /** The cut-off as the user set it (the setting is clamped there; this clamps again). */
    private val cutoff: () -> Float = { DEFAULT_CUTOFF },
    /** Monotonic ms, for the rate limit. */
    private val elapsed: () -> Long,
    /** Where the caption and the embedding run: never the main thread. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    @Volatile private var lastCaptionAt = Long.MIN_VALUE / 2
    private val gate = Any()

    /** Both flags, the switch and the model (asked before anything is read). */
    fun isEnabled(): Boolean = enabled()

    /** True while the last caption is under [RATE_LIMIT_MS] old: the caller then does not even read the photo. */
    fun rateLimited(): Boolean = elapsed() - lastCaptionAt in 0 until RATE_LIMIT_MS

    /**
     * Captions the photo at [jpegPath], ranks [candidates] and returns the outcome. The photo file is the caller's (written, and deleted, there). The model is released right after the caption, whatever happened, so the phone
     * cools down (owner round 5, answer 7). A refusal (thermal, battery, model missing, off) is [OfflineTierResult.REFUSED]: no guess, never an error.
     */
    suspend fun guess(jpegPath: String, candidates: List<OfflineCandidate>): OfflineTierResult {
        if (!enabled()) return OfflineTierResult.OFF
        if (candidates.isEmpty()) return OfflineTierResult.NO_CANDIDATES
        val now = elapsed()
        // One caption per RATE_LIMIT_MS: the slot is claimed before the (slow) caption, so two attempts can never both run.
        val previous: Long
        synchronized(gate) {
            if (now - lastCaptionAt in 0 until RATE_LIMIT_MS) return OfflineTierResult.RATE_LIMITED
            previous = lastCaptionAt; lastCaptionAt = now
        }
        return withContext(dispatcher) { run(jpegPath, candidates, previous) }
    }

    private suspend fun run(jpegPath: String, candidates: List<OfflineCandidate>, previousSlot: Long): OfflineTierResult {
        val raw: String = try {
            val c = try { captioner.caption(jpegPath) } catch (e: CancellationException) { throw e } catch (_: Exception) { return OfflineTierResult.failed() }
            c.getOrNull() ?: run {
                val e = c.exceptionOrNull()
                if (e is CaptionRefused) { synchronized(gate) { lastCaptionAt = previousSlot }; return OfflineTierResult.refused(e.reason.name) } // nothing ran: the slot is not spent
                return OfflineTierResult.failed()
            }
        } finally {
            withContext(NonCancellable) { try { captioner.release() } catch (_: Exception) { } } // the model is freed right after captioning, success or not
        }
        val text = raw.trim()
        if (text.isEmpty()) return OfflineTierResult.failed()
        val vectors = try { embedder.embed(listOf(text) + candidates.map { it.text() }) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        // the caption text is not used past the embedding call: only vectors and buckets go on
        if (vectors == null || vectors.size != candidates.size + 1) return OfflineTierResult.failed()
        val q = unit(vectors[0]) ?: return OfflineTierResult.failed()
        val scored = candidates.mapIndexedNotNull { i, cand -> unit(vectors[i + 1])?.let { v -> cand to dot(q, v) } }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return OfflineTierResult.failed()
        val margin = if (scored.size > 1) best.second - scored[1].second else Float.NaN
        val cut = cutoff().coerceIn(MIN_CUTOFF, MAX_CUTOFF)
        // The margin to the runner-up is only COUNTED (a bucket in the log), it never decides (owner round 6).
        return if (best.second >= cut) OfflineTierResult.maybe(best.first.place, scoreBucket(best.second), marginBucket(margin), candidates.size)
        else OfflineTierResult.below(scoreBucket(best.second), marginBucket(margin), candidates.size)
    }

    private fun unit(v: FloatArray): FloatArray? {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        if (v.isEmpty() || n <= 0.0 || n.isNaN()) return null
        val k = (1.0 / sqrt(n)).toFloat()
        return FloatArray(v.size) { v[it] * k }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        /** Cosine of the photo caption and a candidate's text (bge-small, name + kind + description): the first guess from the Mac test, to be calibrated on the phone. */
        const val DEFAULT_CUTOFF = 0.55f
        const val MIN_CUTOFF = 0.30f
        const val MAX_CUTOFF = 0.90f
        /** One caption per minute: the model is heavy (heat, battery). */
        const val RATE_LIMIT_MS = 60_000L

        /** The candidate band (owner round 5): places within [DEFAULT_RADIUS_M], widened to [WIDE_RADIUS_M] only while the list stays at [WIDE_MAX] or fewer. The user may set the first radius up to [WIDE_RADIUS_M]. */
        const val DEFAULT_RADIUS_M = 150
        const val MIN_RADIUS_M = 50
        const val WIDE_RADIUS_M = 500
        const val WIDE_MAX = 5
        /** The most candidates ever ranked (the sampler's own list is capped lower). */
        const val MAX_CANDIDATES = 16

        /**
         * The candidates of an attempt. [medium] = medium-score places of the embedding matcher, when it has them (always kept). [nearby] = pack places and the user's own pins with the distance (m) to the edge of the place,
         * from the fix. Within [radiusM] first; when that leaves [WIDE_MAX] or fewer, the pool is widened to [WIDE_RADIUS_M] but only if it then still has [WIDE_MAX] or fewer. Nearest first, no duplicates, at most [MAX_CANDIDATES].
         */
        fun select(medium: List<PlaceEntity>, nearby: List<Pair<PlaceEntity, Double>>, radiusM: Int = DEFAULT_RADIUS_M): List<OfflineCandidate> {
            val r = radiusM.coerceIn(MIN_RADIUS_M, WIDE_RADIUS_M)
            val sorted = nearby.sortedBy { it.second }
            val inner = sorted.filter { it.second <= r }
            val wide = if (r < WIDE_RADIUS_M && inner.size <= WIDE_MAX) sorted.filter { it.second <= WIDE_RADIUS_M } else inner
            val pool = if (wide.size <= WIDE_MAX) wide else inner
            return (medium + pool.map { it.first }).distinctBy { it.id }.take(MAX_CANDIDATES).map { OfflineCandidate(it) }
        }

        /** Fixed buckets: never a raw score in the log. */
        internal fun scoreBucket(s: Float): String = when { s.isNaN() -> "na"; s < 0.30f -> "lt30"; s < 0.40f -> "lt40"; s < 0.50f -> "lt50"; s < 0.55f -> "lt55"; s < 0.60f -> "lt60"; s < 0.70f -> "lt70"; else -> "ge70" }
        internal fun marginBucket(m: Float): String = when { m.isNaN() -> "na"; m < 0.03f -> "lt03"; m < 0.06f -> "lt06"; m < 0.10f -> "lt10"; else -> "ge10" }
    }
}

/** The text embedder seam: vectors for [texts], in order (any length per call, not necessarily unit length), or null when the embedder is not available (unbound, paused, offline-refused). */
fun interface TextEmbed {
    suspend fun embed(texts: List<String>): List<FloatArray>?
}

/** A place the caption is compared with. Owner spec 28 Nov: the text is name, kind AND the description (summary), so a caption that matches the description raises the rank; a user's own pin uses its name and note. */
class OfflineCandidate(val place: PlaceEntity) {
    fun text(): String = if (UserPlace.isUserId(place.id)) listOf(place.name, place.summary).filter { it.isNotBlank() }.joinToString(". ").take(TEXT_MAX)
    else listOf(place.name, PlaceCards.kindLabel(place.kind).lowercase(), place.summary.trim().replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(". ").take(TEXT_MAX)

    private companion object { const val TEXT_MAX = 300 }
}

/**
 * What the tier decided. [place] is the single "Maybe" place or null (no guess). [token] is the fixed log token: an outcome name plus count and bucket values, never a name, a coordinate or caption text.
 */
class OfflineTierResult private constructor(val place: PlaceEntity?, val token: String) {
    companion object {
        val OFF = OfflineTierResult(null, "off")
        val NO_CANDIDATES = OfflineTierResult(null, "no_candidates")
        val RATE_LIMITED = OfflineTierResult(null, "rate_limited")
        internal fun refused(reason: String) = OfflineTierResult(null, "refused_${reason.lowercase()}")
        internal fun failed() = OfflineTierResult(null, "failed")
        internal fun below(score: String, margin: String, n: Int) = OfflineTierResult(null, "below(n=$n,score=$score,margin=$margin)")
        internal fun maybe(p: PlaceEntity, score: String, margin: String, n: Int) = OfflineTierResult(p, "maybe(n=$n,score=$score,margin=$margin)")
    }
}
