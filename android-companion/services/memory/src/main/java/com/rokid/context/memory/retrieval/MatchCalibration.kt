package com.rokid.context.memory.retrieval

import kotlin.math.roundToInt

/**
 * Pure calibration maths for Place Guide semantic ranking (1.4.4): given probes (a dwell photo vector with a KNOWN right pin, or none) and the pins' reference vectors (the attached Moment
 * photos), it computes for ONE embedding space the score distributions, the best accept threshold and its precision / recall. Run once per space (Nomic, SigLIP 2) and compare. No I/O,
 * no clock; the real-data runner (services/ai test `MatchCalibrationRealDataTest`) feeds it vectors and writes the report outside git. The report holds counts and milli scores only, no ids.
 *
 * Two views:
 *  - PAIR view (distributions): every (probe, pin) pair is a positive when the pin is the probe's truth, else a negative. Shows how far apart "same place" and "other place" scores sit.
 *  - DECISION view (thresholds): what the shadow would do per probe. The top-1 pin is "shown" when its score >= t; it is "correct" when it is the truth. precision = correct / shown,
 *    recall = correct / probes that have a truth. A probe with NO truth (a place that is not pinned) can only add a wrong card. The best threshold maximises F1 (ties: the higher t).
 */
object MatchCalibration {
    /** [truthPin] null = the photo was taken where no pin is right. */
    class Probe(val id: String, val vector: FloatArray, val truthPin: String?)

    /** One probe against every pin: the pin id and its image-channel score in milli (max cosine over that pin's reference vectors, like [CandidateScorer]). */
    class Trial(val truthPin: String?, val scores: List<Pair<String, Int>>) {
        val top: Pair<String, Int>? get() = scores.maxWithOrNull(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first })
        /** Top-1 minus top-2 in milli; null with fewer than two scored pins (no runner-up: the lead is unbounded). */
        val margin: Int? get() = scores.map { it.second }.sortedDescending().let { if (it.size >= 2) it[0] - it[1] else null }
    }

    class Distribution(val n: Int, val min: Int, val p10: Int, val p50: Int, val p90: Int, val max: Int, val mean: Int) {
        companion object { val EMPTY = Distribution(0, 0, 0, 0, 0, 0, 0) }
    }

    class Point(val threshold: Int, val shown: Int, val correct: Int, val precision: Double, val recall: Double, val f1: Double)

    class Result(
        val positives: Distribution, val negatives: Distribution, val probes: Int, val probesWithTruth: Int, val pins: Int,
        val top1Correct: Int, val best: Point?, val acceptAtPrecision: Int?, val rejectBelow: Int?, val targetPrecision: Double, val curve: List<Point>,
        val acceptAt95: Int? = null, val acceptAt98: Int? = null,
    )

    /** Scores every probe against every pin. A pin without a usable reference vector is skipped. Vectors are L2-normalised here. */
    fun trials(probes: List<Probe>, refs: Map<String, List<FloatArray>>): List<Trial> = probes.map { p ->
        val q = VectorMath.l2(p.vector)
        Trial(p.truthPin, refs.mapNotNull { (pin, vs) ->
            val best = vs.filter { it.size == q.size && it.all(Float::isFinite) && VectorMath.usable(it) }.maxOfOrNull { VectorMath.cosine(q, VectorMath.l2(it)) }
            best?.let { pin to Math.round(it * 1000.0).toInt() }
        }.sortedBy { it.first })
    }


    /** How a pin's single place vector is built for the reference-variant comparison (owner idea, 26 Nov: cluster centre as place reference). */
    enum class RefMode { SINGLE, CENTROID, MEDOID }

    /** Mean of the L2-normalised vectors, then L2. Null when [vs] has no usable vector. */
    fun centroid(vs: List<FloatArray>): FloatArray? {
        val u = vs.filter { it.all(Float::isFinite) && VectorMath.usable(it) }.map { VectorMath.l2(it) }
        if (u.isEmpty()) return null
        val sum = FloatArray(u[0].size)
        for (v in u) if (v.size == sum.size) for (i in sum.indices) sum[i] += v[i]
        return VectorMath.l2(sum)
    }

    /** The member with the highest summed cosine to the others (ties: the first). With one member it is that member. */
    fun medoid(vs: List<FloatArray>): FloatArray? {
        val u = vs.filter { it.all(Float::isFinite) && VectorMath.usable(it) }.map { VectorMath.l2(it) }
        if (u.isEmpty()) return null
        return u.maxByOrNull { a -> u.sumOf { b -> if (b === a) 0.0 else VectorMath.cosine(a, b).toDouble() } }
    }

    /**
     * Leave-one-out trials for one reference variant. [groups]: pin -> its vectors, index 0 = the attached Moment photo (the reference), the rest = probes of that pin. [none]: vectors where no pin is right.
     * The scored probes are the same for every mode (every non-reference vector, plus [none]) so the modes compare on equal ground. SINGLE: the pin's reference is vector 0. CENTROID / MEDOID: the pin's
     * reference is built from its vectors MINUS the probe being scored (reference + the other probes), so a probe never scores against itself; other pins use all their vectors.
     */
    fun looTrials(groups: Map<String, List<FloatArray>>, none: List<FloatArray>, mode: RefMode): List<Trial> {
        fun build(vs: List<FloatArray>): FloatArray? = when (mode) { RefMode.SINGLE -> vs.firstOrNull(); RefMode.CENTROID -> centroid(vs); RefMode.MEDOID -> medoid(vs) }
        val full = groups.mapValues { (_, vs) -> build(vs) }
        val probes = ArrayList<Pair<FloatArray, String?>>(); val heldOut = HashMap<Int, Pair<String, FloatArray?>>()
        for ((pin, vs) in groups) vs.drop(1).forEachIndexed { i, v ->
            val others = vs.filterIndexed { j, _ -> j != i + 1 }
            probes += v to pin
            heldOut[probes.size - 1] = pin to build(others)
        }
        none.forEach { probes += it to null }
        return probes.mapIndexed { idx, (v, truth) ->
            val q = VectorMath.l2(v)
            val refs = full.toMutableMap()
            heldOut[idx]?.let { (pin, ref) -> refs[pin] = ref }
            Trial(truth, refs.mapNotNull { (pin, r) -> r?.takeIf { it.size == q.size }?.let { pin to Math.round(VectorMath.cosine(q, VectorMath.l2(it)) * 1000.0).toInt() } }.sortedBy { it.first })
        }
    }

    /** One row of the margin sweep: top-1 is "shown" when its lead over the runner-up is at least [minMargin] (a missing runner-up always passes). */
    class MarginPoint(val minMargin: Int, val shown: Int, val correct: Int, val precision: Double, val recall: Double)

    /** Margin sweep (WP12b): for each distinct observed margin (and 0), what showing only leads of at least that size would do. Ascending by margin. Offline tooling only; nothing here is a threshold. */
    fun marginSweep(trials: List<Trial>): List<MarginPoint> {
        val withTruth = trials.count { it.truthPin != null }
        val cuts = (trials.mapNotNull { it.margin } + 0).distinct().sorted()
        return cuts.map { m ->
            val passing = trials.filter { it.top != null && (it.margin ?: Int.MAX_VALUE) >= m }
            val correct = passing.count { it.truthPin != null && it.top?.first == it.truthPin }
            MarginPoint(m, passing.size, correct, if (passing.isEmpty()) 1.0 else correct.toDouble() / passing.size, if (withTruth == 0) 0.0 else correct.toDouble() / withTruth)
        }
    }

    /** The lowest top-1 score at which precision is at least [target] (null = never reached). One entry per target, e.g. 0.95 and 0.98. */
    fun acceptAt(trials: List<Trial>, targets: List<Double> = ACCEPT_TARGETS): Map<Double, Int?> = targets.associateWith { evaluate(trials, it).acceptAtPrecision }

    /** The smallest margin whose shown top-1s reach [target] precision, null when none does (or nothing is shown). */
    fun marginForPrecision(trials: List<Trial>, target: Double): Int? = marginSweep(trials).firstOrNull { it.shown > 0 && it.precision >= target }?.minMargin

    val ACCEPT_TARGETS = listOf(0.95, 0.98)

    fun evaluate(trials: List<Trial>, targetPrecision: Double = 0.98): Result {
        val pos = ArrayList<Int>(); val neg = ArrayList<Int>()
        for (t in trials) for ((pin, s) in t.scores) if (pin == t.truthPin) pos += s else neg += s
        val withTruth = trials.count { it.truthPin != null }
        val top1Correct = trials.count { it.truthPin != null && it.top?.first == it.truthPin }
        val thresholds = trials.mapNotNull { it.top?.second }.distinct().sorted()
        val curve = thresholds.map { th ->
            val shown = trials.count { (it.top?.second ?: Int.MIN_VALUE) >= th }
            val correct = trials.count { it.truthPin != null && it.top?.first == it.truthPin && it.top!!.second >= th }
            val prec = if (shown == 0) 1.0 else correct.toDouble() / shown
            val rec = if (withTruth == 0) 0.0 else correct.toDouble() / withTruth
            Point(th, shown, correct, prec, rec, if (prec + rec == 0.0) 0.0 else 2 * prec * rec / (prec + rec))
        }
        val best = curve.maxWithOrNull(compareBy<Point> { it.f1 }.thenBy { it.threshold })
        val acceptAt = acceptFrom(curve, targetPrecision)
        // Below this score the top-1 was never the right pin: a pin scoring under it is safe to reject (no correct top-1 lost). Null when no correct top-1 exists.
        val lowestCorrect = trials.filter { it.truthPin != null && it.top?.first == it.truthPin }.minOfOrNull { it.top!!.second }
        return Result(dist(pos), dist(neg), trials.size, withTruth, trials.flatMap { t -> t.scores.map { it.first } }.toSet().size, top1Correct, best, acceptAt, lowestCorrect, targetPrecision, curve, acceptFrom(curve, 0.95), acceptFrom(curve, 0.98))
    }

    private fun acceptFrom(curve: List<Point>, target: Double): Int? = curve.firstOrNull { it.shown > 0 && it.precision >= target }?.threshold

    internal fun dist(v: List<Int>): Distribution {
        if (v.isEmpty()) return Distribution.EMPTY
        val s = v.sorted()
        fun q(p: Double) = s[((s.size - 1) * p).roundToInt()]
        return Distribution(s.size, s.first(), q(0.10), q(0.50), q(0.90), s.last(), Math.round(s.average()).toInt())
    }

    /** ASCII text, counts and milli scores only. */
    fun report(name: String, r: Result): String = buildString {
        fun d(label: String, x: Distribution) = appendLine("  $label n=${x.n} min=${x.min} p10=${x.p10} p50=${x.p50} p90=${x.p90} max=${x.max} mean=${x.mean}")
        appendLine("space=$name pins=${r.pins} probes=${r.probes} withTruth=${r.probesWithTruth} top1Correct=${r.top1Correct}")
        d("sameplace(milli)", r.positives)
        d("otherplace(milli)", r.negatives)
        val b = r.best
        if (b == null) appendLine("  bestThreshold=none") else appendLine("  bestThreshold=${b.threshold} shown=${b.shown} correct=${b.correct} precision=${pct(b.precision)} recall=${pct(b.recall)} f1=${pct(b.f1)}")
        appendLine("  acceptAtPrecision${(r.targetPrecision * 100).roundToInt()}=${r.acceptAtPrecision ?: "none"} rejectBelow=${r.rejectBelow ?: "none"}")
        appendLine("  acceptAt95=${r.acceptAt95 ?: "none"} acceptAt98=${r.acceptAt98 ?: "none"}")
        appendLine("  separation(p10 same - p90 other)=${r.positives.p10 - r.negatives.p90}")
    }

    /** The margin sweep as ASCII rows: counts and milli only. */
    fun marginReport(name: String, trials: List<Trial>): String = buildString {
        appendLine("space=$name marginSweep (minMargin shown correct precision recall)")
        for (p in marginSweep(trials)) appendLine("  ${p.minMargin} ${p.shown} ${p.correct} ${pct(p.precision)} ${pct(p.recall)}")
        appendLine("  marginForPrecision95=${marginForPrecision(trials, 0.95) ?: "none"} marginForPrecision98=${marginForPrecision(trials, 0.98) ?: "none"}")
    }

    private fun pct(x: Double) = "${(x * 1000).roundToInt() / 10.0}%"
}
