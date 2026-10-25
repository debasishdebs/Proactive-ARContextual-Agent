package com.rokid.context.memory.forget

/**
 * How far around a forgotten photo's capture time marks, traces and the results that read them are removed with it (the "photo Forget radius"). The ONE accessor: [ms], read at
 * Forget time by [ForgetService.dropCanvasOutputs] (every photo Forget, a retention expiry and a Moment Forget go through it). The phone binds [provider] once at start to the stored setting
 * (Settings > Capture); the default is [DEFAULT_MS] (10 s, the old `PHOTO_LINK_MS`) and a value that is not one of the [PRESETS_MS] falls back to it, so a bad stored value never widens or removes the link.
 */
object ForgetRadius {
    const val DEFAULT_MS = 10_000L
    val PRESETS_MS = listOf(5_000L, 10_000L, 15_000L, 30_000L)

    @Volatile var provider: () -> Long = { DEFAULT_MS }

    fun ms(): Long = clean(provider())

    fun clean(ms: Long): Long = if (ms in PRESETS_MS) ms else DEFAULT_MS
}
