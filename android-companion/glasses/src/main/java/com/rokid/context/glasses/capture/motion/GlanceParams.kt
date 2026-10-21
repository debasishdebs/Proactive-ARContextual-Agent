package com.rokid.context.glasses.capture.motion

/** Tunables of the glance model (spec A3 section 3, novelty and rate cap sections 4-5). Defaults are first-principles values. */
data class GlanceParams(
    val onsetRadS: Float = 0.6f,
    val releaseRadS: Float = 0.3f,
    val bridgeMs: Long = 250,
    val minBoutMs: Long = 300,
    val minBoutRad: Float = 0.26f,
    val settleMs: Long = 1_500,
    val maxDtMs: Long = 100,
    val deadbandRadS: Float = 0.05f,
    val noveltyWindowMs: Long = 20_000,
    val noveltyMinRad: Float = 0.30f,
    val bigTurnRad: Float = 0.8f,
    val capPerMin: Int = 4,
    val restCapPerMin: Int = 2,
) {
    companion object {
        /** The pre-A3 dwell: any sample over 0.6 rad/s, then 1 s without one; no novelty gate, no cap. */
        val LEGACY = GlanceParams(
            releaseRadS = 0.6f, minBoutMs = 0, minBoutRad = 0f, settleMs = 1_000, noveltyMinRad = 0f,
            bigTurnRad = 0f, capPerMin = 1_000, restCapPerMin = 1_000,
        )
    }
}
