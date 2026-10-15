package com.rokid.context.data.runtime

import android.content.Context

object PipelineStatsStore {
    private const val PREF = "pipeline_stats"
    private const val K_SUMM_TRIGGERED = "summ_triggered"
    private const val K_SUMM_SUCCEEDED = "summ_succeeded"
    private const val K_SUMM_FAILED = "summ_failed"
    private const val K_FRAME_CAPTURED = "frame_captured"
    /** Count-only Diagnostics entry: a v2 JPEG ingested with defaults after its sidecar stayed lost for 24 h. */
    const val K_V2_SIDECAR_LOST = "v2_sidecar_lost"

    data class Stats(
        val summarizerTriggered: Int,
        val summarizerSucceeded: Int,
        val summarizerFailed: Int,
        val framesCaptured: Int,
    )

    fun incSummarizerTriggered(context: Context) = inc(context, K_SUMM_TRIGGERED)
    fun incSummarizerSucceeded(context: Context) = inc(context, K_SUMM_SUCCEEDED)
    fun incSummarizerFailed(context: Context) = inc(context, K_SUMM_FAILED)
    fun incFrameCaptured(context: Context) = inc(context, K_FRAME_CAPTURED)
    fun incSidecarLost(context: Context) = inc(context, K_V2_SIDECAR_LOST)
    fun sidecarLost(context: Context): Int = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(K_V2_SIDECAR_LOST, 0)

    fun get(context: Context): Stats {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Stats(
            summarizerTriggered = p.getInt(K_SUMM_TRIGGERED, 0),
            summarizerSucceeded = p.getInt(K_SUMM_SUCCEEDED, 0),
            summarizerFailed = p.getInt(K_SUMM_FAILED, 0),
            framesCaptured = p.getInt(K_FRAME_CAPTURED, 0),
        )
    }

    private fun inc(context: Context, key: String) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val next = p.getInt(key, 0) + 1
        p.edit().putInt(key, next).apply()
    }
}
