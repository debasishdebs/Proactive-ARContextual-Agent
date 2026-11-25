package com.rokid.context.memory.retrieval

/**
 * The accept / reject bands of Place Guide semantic ranking (1.4.4), one pair per (embedding space, channel). Scores are cosine x 1000 ("milli", the unit of [CandidateScorer]).
 * Cosines of different spaces are NOT comparable (SigLIP 2 image-to-text cosines sit far lower than Nomic's), so every space and channel has its own constants.
 *
 * DEFAULT: NOT SET. Until the calibration harness ([MatchCalibration]) has run on real Moments, [accept] is [NOT_SET_ACCEPT] (nothing is ever accepted) and [reject] is [NOT_SET_REJECT]
 * (nothing is ever rejected), so [band] always answers [Band.BORDERLINE] and the model keeps deciding. Shadow logging reports the band, so the log shows what a calibrated value would do only
 * after a value is set here. To set one: take "bestThreshold" (for [accept]: "acceptAtPrecision") and "rejectBelow" from the calibration report, put the milli values into the constants below,
 * and bump [VERSION]. Ranking never skips the model call while the shadow flags are the only consumers (this object is read by the shadow log only).
 */
object MatchThresholds {
    /** Bump whenever any constant below changes; the shadow compare line prints it as `thrV`. 0 = nothing calibrated. */
    const val VERSION = 0

    const val NOT_SET_ACCEPT = Int.MAX_VALUE
    const val NOT_SET_REJECT = Int.MIN_VALUE

    enum class Space { NOMIC, SIGLIP2 }
    enum class Band { ACCEPT, BORDERLINE, REJECT }

    /** A pin's reference photo against the dwell photo (image to image). */
    const val NOMIC_IMAGE_ACCEPT_MILLI = NOT_SET_ACCEPT
    const val NOMIC_IMAGE_REJECT_MILLI = NOT_SET_REJECT
    const val SIGLIP2_IMAGE_ACCEPT_MILLI = NOT_SET_ACCEPT
    const val SIGLIP2_IMAGE_REJECT_MILLI = NOT_SET_REJECT

    /** A place name text anchor against the dwell photo (cross-modal). */
    const val NOMIC_TEXT_ACCEPT_MILLI = NOT_SET_ACCEPT
    const val NOMIC_TEXT_REJECT_MILLI = NOT_SET_REJECT
    const val SIGLIP2_TEXT_ACCEPT_MILLI = NOT_SET_ACCEPT
    const val SIGLIP2_TEXT_REJECT_MILLI = NOT_SET_REJECT

    /** An accept also needs the top-1 to lead the runner-up by at least this many milli (when there is a runner-up). Same for every space until calibrated. */
    const val MIN_ACCEPT_MARGIN_MILLI = 0

    /** Space of a [com.rokid.context.memory.ImageTextEmbedder.spaceId], null when unknown (then no band is claimed). */
    fun spaceOf(spaceId: String): Space? = when {
        spaceId.startsWith("siglip2") -> Space.SIGLIP2
        spaceId.startsWith("nomic") -> Space.NOMIC
        else -> null
    }

    fun accept(space: Space, channel: CandidateScorer.Channel): Int = when (channel) {
        CandidateScorer.Channel.IMAGE -> if (space == Space.NOMIC) NOMIC_IMAGE_ACCEPT_MILLI else SIGLIP2_IMAGE_ACCEPT_MILLI
        CandidateScorer.Channel.TEXT -> if (space == Space.NOMIC) NOMIC_TEXT_ACCEPT_MILLI else SIGLIP2_TEXT_ACCEPT_MILLI
        CandidateScorer.Channel.NONE -> NOT_SET_ACCEPT
    }

    fun reject(space: Space, channel: CandidateScorer.Channel): Int = when (channel) {
        CandidateScorer.Channel.IMAGE -> if (space == Space.NOMIC) NOMIC_IMAGE_REJECT_MILLI else SIGLIP2_IMAGE_REJECT_MILLI
        CandidateScorer.Channel.TEXT -> if (space == Space.NOMIC) NOMIC_TEXT_REJECT_MILLI else SIGLIP2_TEXT_REJECT_MILLI
        CandidateScorer.Channel.NONE -> NOT_SET_REJECT
    }

    /** The band of a top-1 score. [margin] null = no runner-up. Total: an unknown space or an empty result is BORDERLINE. */
    fun band(space: Space?, channel: CandidateScorer.Channel, topMilli: Int?, margin: Int?): Band {
        if (space == null || topMilli == null || channel == CandidateScorer.Channel.NONE) return Band.BORDERLINE
        if (topMilli >= accept(space, channel) && (margin == null || margin >= MIN_ACCEPT_MARGIN_MILLI)) return Band.ACCEPT
        if (topMilli < reject(space, channel)) return Band.REJECT
        return Band.BORDERLINE
    }

    /** Band of a scorer result for an embedding space id. */
    fun band(spaceId: String, r: CandidateScorer.Result): Band = band(spaceOf(spaceId), r.channel, r.top1?.milli, r.margin)
}
