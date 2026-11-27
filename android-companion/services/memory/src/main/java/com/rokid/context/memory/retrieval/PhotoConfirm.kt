package com.rokid.context.memory.retrieval

/**
 * Layer 1 of the layered place confirmation (design 2026-11-27): a pure threshold decision of the photo against the stored reference vectors of the nearby places. No I/O, no clock.
 *
 * Per reference, with the numbers of its [RefKind]: score >= HIGH = "high", score >= FLOOR (or the floor is off) = "mid", otherwise "below". A place is as good as its best reference: ANY high reference makes the whole place high;
 * a place is "below" only when ALL its references are below (a reference whose kind has no floor never counts as below). A place without a usable reference stays in the list ([Level.NO_REF]).
 *  - exactly ONE place high, and it leads the best other place by at least the MARGIN of its winning reference's kind (no other place = no margin needed) -> [Outcome.ACCEPT];
 *  - otherwise the narrowed list = high + mid + no-reference places, in the input order (two or more high places are all kept: nothing is accepted) -> [Outcome.MEDIUM], or [Outcome.BELOW] when no place with a reference
 *    survived, or [Outcome.NO_REF] when no place had one at all, or [Outcome.SKIP] when there was no usable query.
 * Boundaries are inclusive (a score equal to HIGH is high, equal to FLOOR is mid). The result is the same for any input order of equal inputs.
 */
object PhotoConfirm {
    class Ref(val kind: RefKind, val vector: FloatArray)
    class PlaceRefs(val placeId: String, val refs: List<Ref>)

    enum class Level { HIGH, MID, BELOW, NO_REF }
    enum class Outcome { ACCEPT, MEDIUM, BELOW, NO_REF, SKIP }

    /** [milli] = best reference score (null without a usable reference); [kind] = the kind of that best reference. */
    data class PlaceScore(val placeId: String, val milli: Int?, val kind: RefKind?, val level: Level)

    class Result(
        val outcome: Outcome,
        /** Set only for [Outcome.ACCEPT]. */
        val accepted: PlaceScore?,
        /** The narrowed list for Layer 2/3, in input order (the whole input for SKIP and NO_REF; only the accepted place for ACCEPT). */
        val candidates: List<String>,
        val scores: List<PlaceScore>,
        /** accepted score minus the best other score (null: no other scored place), or the same for the best place when nothing was accepted. */
        val margin: Int?,
    ) {
        val withRef: Int get() = scores.count { it.level != Level.NO_REF }
        val noRef: Int get() = scores.count { it.level == Level.NO_REF }
        val topMilli: Int? get() = scores.mapNotNull { it.milli }.maxOrNull()
    }

    /** [queries] = [MultiFrameQuery.Query.scoringVectors] (one vector = mean or single frame; several = best-of-k). */
    fun decide(queries: List<FloatArray>, places: List<PlaceRefs>, cfg: PhotoMatchConfig = PhotoMatchConfig.DEFAULT): Result {
        val order = places.map { it.placeId }.distinct()
        val qs = queries.filter { q -> q.isNotEmpty() && q.all { it.isFinite() } && VectorMath.usable(q) }.map { VectorMath.l2(it) }
        if (qs.isEmpty()) return Result(Outcome.SKIP, null, order, order.map { PlaceScore(it, null, null, Level.NO_REF) }, null)
        val c = cfg.clamped()
        val byId = LinkedHashMap<String, MutableList<Ref>>()
        for (p in places) byId.getOrPut(p.placeId) { ArrayList() } += p.refs
        // per place: best score over every reference and query vector, and the level over all references
        class Acc(val id: String, var best: Int?, var bestKind: RefKind?, var high: Boolean, var mid: Boolean, var winMilli: Int?, var winKind: RefKind?)
        val accs = order.map { id ->
            val a = Acc(id, null, null, false, false, null, null)
            for (r in byId.getValue(id)) {
                val v = r.vector
                if (v.isEmpty() || qs.first().size != v.size || !v.all { it.isFinite() } || !VectorMath.usable(v)) continue
                val s = qs.maxOf { Math.round(VectorMath.cosine(it, v) * 1000.0).toInt() }
                val t = c.of(r.kind)
                if (a.best == null || s > a.best!!) { a.best = s; a.bestKind = r.kind }
                if (s >= t.high) { a.high = true; if (a.winMilli == null || s > a.winMilli!!) { a.winMilli = s; a.winKind = r.kind } }
                else if (t.floorOff || s >= t.floor) a.mid = true
            }
            a
        }
        val scores = accs.map { a ->
            val level = when { a.best == null -> Level.NO_REF; a.high -> Level.HIGH; a.mid -> Level.MID; else -> Level.BELOW }
            PlaceScore(a.id, a.best, a.bestKind, level)
        }
        val scored = scores.filter { it.level != Level.NO_REF }
        if (scored.isEmpty()) return Result(Outcome.NO_REF, null, order, scores, null)
        val highs = accs.filter { it.high }
        if (highs.size == 1) {
            val a = highs.single()
            val bestOther = scored.filter { it.placeId != a.id }.mapNotNull { it.milli }.maxOrNull()
            val lead = bestOther?.let { a.winMilli!! - it }
            val need = c.of(a.winKind!!).margin
            if (lead == null || lead >= need) {
                return Result(Outcome.ACCEPT, PlaceScore(a.id, a.winMilli, a.winKind, Level.HIGH), listOf(a.id), scores, lead)
            }
        }
        val keep = scores.filter { it.level != Level.BELOW }.map { it.placeId }
        val sorted = scored.mapNotNull { it.milli }.sortedDescending()
        val margin = if (sorted.size >= 2) sorted[0] - sorted[1] else null
        val anyKept = scores.any { it.level == Level.HIGH || it.level == Level.MID }
        return Result(if (anyKept) Outcome.MEDIUM else Outcome.BELOW, null, keep, scores, margin)
    }

    /** Fixed log buckets (no raw score): the milli of a place and the margin. */
    fun scoreBucket(milli: Int?): String = when { milli == null -> "na"; milli < 700 -> "lt700"; milli < 800 -> "lt800"; milli < 850 -> "lt850"; milli < 900 -> "lt900"; milli < 945 -> "lt945"; milli < 970 -> "lt970"; else -> "ge970" }
    fun marginBucket(milli: Int?): String = when { milli == null -> "na"; milli < 0 -> "neg"; milli < 10 -> "lt10"; milli < 25 -> "lt25"; milli < 50 -> "lt50"; else -> "ge50" }
}
