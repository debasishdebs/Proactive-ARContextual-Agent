package com.rokid.context.core.model.signals

import java.security.MessageDigest

/**
 * The editable thresholds of the glasses motion classifier (v0.9.7 A, ruling 6). Defaults ARE the constants `MotionClassifier` and the shadow
 * `HeuristicMotionModel` shipped with, so behaviour is unchanged until the user edits one. On the wire only NON-default keys travel (`HudSettings.classifier`).
 * Every value passes [clean]: unknown keys dropped, non-finite dropped, clamped into [RANGES], a min frequency kept below its max, defaults dropped.
 */
data class ClassifierThresholds(
    val headMovingRadS: Float = 0.6f,
    val walkVariance: Float = 0.5f, val walkMinHz: Double = 1.5, val walkMaxHz: Double = 2.5,
    val relaxedVariance: Float = 0.25f, val relaxedMinHz: Double = 1.2, val relaxedMaxHz: Double = 2.8, val relaxedHoldMs: Long = 2_000L,
    val heurAccVar: Float = 0.25f, val heurMinHz: Float = 1.2f, val heurMaxHz: Float = 2.8f, val heurAboveOnFrac: Float = 0.3f,
) {
    companion object {
        val DEFAULT = ClassifierThresholds()

        /** key to (default, min, max). The ranges keep each min frequency at or below 2.0 and each max at or above 2.2 (walk: 2.0), so a pair can only meet at the walk bound. */
        val RANGES: Map<String, Triple<Double, Double, Double>> = linkedMapOf(
            "headMovingRadS" to Triple(0.6, 0.3, 1.2),
            "walkVariance" to Triple(0.5, 0.2, 1.5), "walkMinHz" to Triple(1.5, 1.0, 2.0), "walkMaxHz" to Triple(2.5, 2.0, 3.5),
            "relaxedVariance" to Triple(0.25, 0.1, 0.6), "relaxedMinHz" to Triple(1.2, 0.8, 1.6), "relaxedMaxHz" to Triple(2.8, 2.2, 3.5),
            "relaxedHoldMs" to Triple(2_000.0, 1_000.0, 5_000.0),
            "heurAccVar" to Triple(0.25, 0.1, 0.8), "heurMinHz" to Triple(1.2, 0.8, 1.8), "heurMaxHz" to Triple(2.8, 2.2, 3.5), "heurAboveOnFrac" to Triple(0.3, 0.1, 0.8),
        )
        private val PAIRS = listOf("walkMinHz" to "walkMaxHz", "relaxedMinHz" to "relaxedMaxHz", "heurMinHz" to "heurMaxHz")

        private fun round(x: Double) = Math.round(x * 1000.0) / 1000.0

        /** The canonical override map: only valid, clamped, non-default entries. */
        fun clean(raw: Map<String, Double>): Map<String, Double> {
            val m = LinkedHashMap<String, Double>()
            for ((k, d) in RANGES) {
                val v = raw[k]?.takeIf { it.isFinite() } ?: continue
                m[k] = round(v.coerceIn(d.second, d.third))
            }
            for ((lo, hi) in PAIRS) {
                val a = m[lo] ?: RANGES.getValue(lo).first; val b = m[hi] ?: RANGES.getValue(hi).first
                if (a >= b) { m.remove(lo); m.remove(hi) }
            }
            return m.filter { (k, v) -> v != RANGES.getValue(k).first }
        }

        fun from(raw: Map<String, Double>): ClassifierThresholds {
            val c = clean(raw)
            fun d(k: String) = c[k] ?: RANGES.getValue(k).first
            return ClassifierThresholds(
                d("headMovingRadS").toFloat(), d("walkVariance").toFloat(), d("walkMinHz"), d("walkMaxHz"),
                d("relaxedVariance").toFloat(), d("relaxedMinHz"), d("relaxedMaxHz"), d("relaxedHoldMs").toLong(),
                d("heurAccVar").toFloat(), d("heurMinHz").toFloat(), d("heurMaxHz").toFloat(), d("heurAboveOnFrac").toFloat(),
            )
        }

        /** `"default"` with no overrides, else 12 hex of SHA-256 over the canonical `key=value;` list (what the glasses report and the trace records). */
        fun hash(raw: Map<String, Double>): String {
            val c = clean(raw)
            if (c.isEmpty()) return "default"
            val text = c.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12)
        }
    }
}
