package com.rokid.context.glasses.capture.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Work/rest cycle (spec P1.5): after W minutes of sampling, rest R minutes. The owner calls [onSampling] on each
 * sampling tick and [onPaused] while it is not sampling (not worn, heat, rest). [resting] holds the end of the
 * current rest (ms on [clock]) or null. Any pause >= R, a finished rest included, restarts the work clock.
 * Settings are read live; the phone UI already enforces "W > 45 means R >= 15", and it is clamped again here.
 */
class WorkRest(private val clock: () -> Long, private val workMin: () -> Int, private val restMin: () -> Int) {
    private val _resting = MutableStateFlow<Long?>(null)
    val resting: StateFlow<Long?> = _resting

    private var workStartMs: Long? = null
    private var pausedAtMs: Long? = null

    private fun workMs() = workMin().coerceAtLeast(1) * MIN_MS
    private fun restMs(): Long {
        val w = workMin()
        val r = restMin().coerceAtLeast(1)
        return (if (w > 45) maxOf(r, 15) else r) * MIN_MS
    }

    @Synchronized
    fun onSampling() {
        val now = clock()
        _resting.value?.let { until ->
            if (now < until) return // still resting: the owner should not be sampling
            _resting.value = null
            workStartMs = null // the rest itself was a pause of >= R
            pausedAtMs = null
        }
        pausedAtMs?.let { if (now - it >= restMs()) workStartMs = null }
        pausedAtMs = null
        val start = workStartMs ?: now.also { workStartMs = it }
        if (now - start >= workMs()) {
            _resting.value = now + restMs()
            pausedAtMs = now
            workStartMs = null
        }
    }

    @Synchronized
    fun onPaused() {
        val now = clock()
        _resting.value?.let { if (now >= it) _resting.value = null }
        if (pausedAtMs == null) pausedAtMs = now
    }

    private companion object { const val MIN_MS = 60_000L }
}
