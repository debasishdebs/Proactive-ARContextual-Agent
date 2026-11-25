package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.retrieval.CandidateScorer
import com.rokid.context.memory.retrieval.MatchThresholds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

typealias ShadowScorer = suspend (frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean) -> ShadowOutcome

/**
 * SigLIP 2 as a SECOND shadow embedder next to Nomic (1.4.4). It wraps the two scorers behind the hook [PlaceSampler] already has:
 *  - the Nomic outcome is returned UNCHANGED and at its own pace, so the existing `place attempt` line, its budget and every outcome are exactly what they were;
 *  - SigLIP runs beside it, behind its own flag ([enabled]), on [scope], with its own budget; its result only ever feeds ONE extra counts-only `place shadow2` line.
 * Never reached with Nomic's flag off. A failure, a timeout or a missing model of the SigLIP side is a status in the line (`na`, `err`, `timeout`), never an exception to the caller.
 *
 * The line carries no id, name, coordinate, text or raw score: status words, buckets, booleans, counts and durations. Scores of the two spaces are on different scales and are never
 * mixed; the bands come from [MatchThresholds] (uncalibrated = always `borderline`).
 */
class SiglipShadowCompare(
    private val nomic: ShadowScorer,
    private val siglip: ShadowScorer,
    private val enabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** Called when the SigLIP side finished (any way). Production passes nothing: releasing an ORT tower under an in-flight run is unsafe. */
    private val afterSiglip: () -> Unit = {},
    private val siglipSpace: () -> String = { "siglip2" },
    private val nomicSpace: () -> String = { "nomic" },
    private val budgetMs: Long = BUDGET_MS,
) {
    suspend fun score(frameId: String, hits: List<Pair<PlaceEntity, Double>>, live: () -> Boolean): ShadowOutcome {
        if (!enabled()) return nomic(frameId, hits, live)
        val sig = scope.async {
            try {
                withTimeoutOrNull(budgetMs) { siglip(frameId, hits) { enabled() && live() } }?.let { Side(it, null) } ?: Side(null, "timeout")
            } catch (c: CancellationException) { throw c } catch (_: Throwable) { Side(null, "err") } finally { try { afterSiglip() } catch (_: Throwable) { } }
        }
        val n = try { nomic(frameId, hits, live) } catch (t: Throwable) { sig.cancel(); throw t }
        scope.launch { try { line(n, sig.await(), hits.size)?.let(log) } catch (c: CancellationException) { throw c } catch (_: Throwable) { } }
        return n
    }

    private class Side(val outcome: ShadowOutcome?, val tag: String?)

    private fun line(n: ShadowOutcome, side: Side, candidates: Int): String? {
        val s = side.outcome
        if (n.kind == ShadowOutcome.Kind.DROPPED || s?.kind == ShadowOutcome.Kind.DROPPED || n.kind == ShadowOutcome.Kind.OFF) return null // a fenced attempt logs nothing, like the first shadow
        val rn = n.result; val rs = s?.result
        val sb = StringBuilder("place shadow2 v=1 thrV=${MatchThresholds.VERSION} cand=$candidates nomic=${status(n)} sig=${if (s == null) side.tag else status(s)}")
        if (rn != null && rs != null) {
            val top3 = rn.top.map { it.id }.toSet().intersect(rs.top.map { it.id }.toSet()).size
            sb.append(" chN=${rn.channel.name.lowercase()} chS=${rs.channel.name.lowercase()} top1Same=${rn.top1?.id != null && rn.top1?.id == rs.top1?.id} top3Overlap=$top3 topN=${rn.top.size}/${rs.top.size}")
            sb.append(" scoreN=${rn.top1?.let { PlaceShadow.scoreBucket(it.milli) } ?: "na"} scoreS=${rs.top1?.let { PlaceShadow.scoreBucket(it.milli) } ?: "na"}")
            sb.append(" marginN=${PlaceShadow.marginBucket(rn.margin)} marginS=${PlaceShadow.marginBucket(rs.margin)}")
            sb.append(" bandN=${MatchThresholds.band(nomicSpace(), rn).name.lowercase()} bandS=${MatchThresholds.band(siglipSpace(), rs).name.lowercase()}")
            sb.append(" msN=${n.embedMs} msS=${s?.embedMs}")
        }
        return sb.toString()
    }

    private fun status(o: ShadowOutcome) = when (o.kind) {
        ShadowOutcome.Kind.SCORED -> if (o.result?.channel == CandidateScorer.Channel.NONE) "empty" else "ok"
        ShadowOutcome.Kind.UNAVAILABLE -> "na"
        else -> "err"
    }

    companion object {
        /** Own budget, apart from the sampler's 2.5 s: a cold ORT session load plus a reference-photo embed can take seconds, and nothing waits for it. */
        const val BUDGET_MS = 20_000L
    }
}
