package com.rokid.context.capture.trace

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.MotionClassifier

/**
 * The IMU features of one canonical PHONE-clock second of a trace. The seven numbers are the ones `MotionClassifier.features()` returns once a second on the glasses (the order of
 * `SecondFeatures`): gyro max, mean and rms of the magnitude, the fraction of gyro samples over the onset threshold, the linear-acceleration window variance, the step frequency and the
 * head rotation travelled in the second. [state] is the classifier's motion state at the end of the second, [context] the last recorded `cx` context state at or before it (null before the
 * first one), [nGyro] and [nAcc] the samples that fell in the second (a partial first or last second shows as a small count). Numbers, enum names and counts only.
 */
data class SecondFeatureRow(
    val phoneSecond: Long,
    val gyroMax: Double, val gyroMean: Double, val gyroRms: Double, val aboveOnFrac: Double, val accVar: Double, val stepHz: Double, val rotRad: Double,
    val state: MotionState, val context: ContextState?, val nGyro: Int, val nAcc: Int,
)

/**
 * Per-second sensor features of one imported trace, on the phone clock (moment-model spec section 4, canonical windows). The frozen trace holds raw gyro and accel samples (`g`, `a`) and
 * the context changes (`cx`), not per-second feature rows, so the rows are COMPUTED here with the same [MotionClassifier] the glasses run, fed the way replay feeds it: in append order,
 * with each sample's SENSOR time `sMs` (batched delivery must not collapse the sample spacing); `tMs` (delivery time, elapsed) only decides which phone second a sample belongs to, through
 * the trace's own [ClockMap]. A sample the map cannot place (another boot, farther than the map's slack from the trace, a wall or phone clock jump, no phone offset) belongs to no second
 * and is dropped: a second is never guessed. The classifier runs continuously across seconds (its window and head-hold state carry over, as live); only its accumulators are taken at each
 * second boundary. Pure and deterministic; reads nothing but its arguments.
 */
object TraceSecondFeatures {
    fun compute(manifest: Manifest, events: Sequence<TraceEvent>): List<SecondFeatureRow> {
        val map = ClockMap.of(manifest)
        val classifier = MotionClassifier({ 0L }).also { it.setThresholds(com.rokid.context.core.model.signals.ClassifierThresholds.from(manifest.snapshot.classifier)) }
        val rows = ArrayList<SecondFeatureRow>()
        val contexts = ArrayList<Pair<Long, ContextState>>() // (phone second of the cx event, state), in append order
        var current = Long.MIN_VALUE; var nGyro = 0; var nAcc = 0

        fun flush() {
            if (current == Long.MIN_VALUE) return // a second exists only once a gyro or accel sample entered it
            val f = classifier.features()
            rows += SecondFeatureRow(
                current, f.gyroMax.toDouble(), f.gyroMean.toDouble(), f.gyroRms.toDouble(), f.aboveOnFrac.toDouble(), f.accVariance.toDouble(), f.stepHz, f.rotRad.toDouble(),
                f.state, null, nGyro, nAcc,
            )
        }
        fun secondOf(tMs: Long): Long? = map.phoneWall(tMs, manifest.bootId)?.let { Math.floorDiv(it, 1000L) }
        fun enter(second: Long) {
            if (second == current) return
            flush(); current = maxOf(current, second); nGyro = 0; nAcc = 0
        }

        for (e in events) when (e) {
            is TraceEvent.Gyro -> secondOf(e.tMs)?.let { enter(it); classifier.onGyro(e.x, e.y, e.z, e.sMs); nGyro++ }
            is TraceEvent.Accel -> secondOf(e.tMs)?.let { enter(it); classifier.onLinearAccel(e.x, e.y, e.z, e.sMs); nAcc++ }
            is TraceEvent.Ctx -> secondOf(e.tMs)?.let { contexts += it to e.state }
            else -> Unit
        }
        flush()
        return rows.map { r -> r.copy(context = contexts.lastOrNull { it.first <= r.phoneSecond }?.second) }
    }
}
