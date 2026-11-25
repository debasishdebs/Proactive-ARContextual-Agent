package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.retrieval.CandidateScorer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.4.6 pack Moments (design 2026-11-25-pack-moments-design.md). ONE candidate interface for the matcher: a user pin with its reference Moment ([PinMoment]) and a pack place (`wd:` / `osm:`) turned into a
 * Moment ([PackMoment]) both reach [CandidateScorer] through [MomentCandidate], so every source takes the same matching path. SHADOW ONLY: the flag `packMoments` (default OFF) changes the text a pack place is
 * scored with inside the shadow and fills [PackMomentStats]; it never changes a card, a model call or what the user sees.
 *
 * A pack Moment is DERIVED, IN MEMORY, PER ATTEMPT: built from the pack place row the pack already stores. It has no photo and no location of the user (its [distanceM] and [edgeGapM] are only tie-breakers
 * the sampler already computed), no database row (so it can never be a stored Moment and never reaches an export), and no network call (the pack place is already on the phone, so Offline only changes nothing).
 */
internal enum class MomentOrigin { PIN, PACK }

internal interface MomentCandidate {
    val id: String
    val origin: MomentOrigin
    /** The text the matcher embeds for this candidate, or null when it has none. */
    val anchorText: String?
    /** True when the text is public pack text (safe for the in-memory text cache); a pin's text is never cached. */
    val cacheableText: Boolean
    val edgeGapM: Double
    val distanceM: Double
    fun toScorer(imageRefs: List<FloatArray>, textVector: FloatArray?): CandidateScorer.Candidate =
        CandidateScorer.Candidate(id, imageRefs = imageRefs, textAnchor = textVector, edgeGapM = edgeGapM, distanceM = distanceM)
}

/** A user pin as a matcher candidate. One-way: the pin refers to its Moment (the image refs come from [PinReferenceVectors]); a Moment holds no pin. */
internal class PinMoment(private val place: PlaceEntity, override val distanceM: Double) : MomentCandidate {
    override val id get() = place.id
    override val origin get() = MomentOrigin.PIN
    override val anchorText: String get() = PlaceShadow.anchorText(place)
    override val cacheableText get() = false
    override val edgeGapM get() = (distanceM - place.radiusM).coerceAtLeast(0.0)
}

/**
 * A pack place as a matcher candidate. [rich] = the plan's anchor `name. kind. summary (first 300 chars)` (flag `packMoments` ON); otherwise the shadow's older `name. kind`. Text only: no photo, so it scores on
 * the TEXT channel; a reference image per pack place is a later step and needs a fetch the design note asks the owner about.
 */
internal class PackMoment(private val place: PlaceEntity, override val distanceM: Double, private val rich: Boolean) : MomentCandidate {
    override val id get() = place.id
    override val origin get() = MomentOrigin.PACK
    override val anchorText: String get() = if (rich) richText(place) else PlaceShadow.anchorText(place)
    override val cacheableText get() = true
    override val edgeGapM get() = (distanceM - place.radiusM).coerceAtLeast(0.0)

    companion object {
        const val SUMMARY_CHARS = 300
        fun richText(p: PlaceEntity): String {
            val s = p.summary.trim().take(SUMMARY_CHARS)
            return if (s.isEmpty()) PlaceShadow.anchorText(p) else "${PlaceShadow.anchorText(p)}. $s"
        }
    }
}

internal object MomentCandidates {
    /** Every hit as a [MomentCandidate]: a user pin is a [PinMoment]; anything else is a [PackMoment] ([packMoments] = the Developer flag, picks the richer text). */
    fun of(hits: List<Pair<PlaceEntity, Double>>, packMoments: Boolean): List<MomentCandidate> =
        hits.map { (p, d) -> if (UserPlace.isUserId(p.id)) PinMoment(p, d) else PackMoment(p, d, rich = packMoments) }
}

/**
 * Counts only, in memory, for the Developer count line and the `place pack moments` log line: how many pack and pin candidates the last shadow attempt saw, and how many attempts so far. No id, name, text or
 * coordinate. Registered in [ShadowCaches] (item `place_shadow.pack_moment_counts`), so Forget and Delete all reset it.
 */
object PackMomentStats {
    private val lastPack = AtomicInteger(0)
    private val lastPin = AtomicInteger(0)
    private val attempts = AtomicInteger(0)

    fun record(pack: Int, pin: Int) { lastPack.set(pack); lastPin.set(pin); attempts.incrementAndGet() }
    fun clear() { lastPack.set(0); lastPin.set(0); attempts.set(0) }

    fun line(pack: Int, pin: Int) = "place pack moments v=1 pack=$pack pin=$pin"

    /** The Developer count line. */
    fun summary(): String = "Last attempt: ${lastPack.get()} pack and ${lastPin.get()} pin candidates; ${attempts.get()} attempts since start or last Forget."
}
