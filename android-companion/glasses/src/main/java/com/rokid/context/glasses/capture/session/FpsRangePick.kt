package com.rokid.context.glasses.capture.session

/** Picks the camera AE target fps range for the sampling stream: we analyse at most 3 fps, so stream at <= [CAP_FPS]. */
object FpsRangePick {
    const val CAP_FPS = 15
    private const val MIN_LAST_FPS = 5

    /** Lowest `last` within [MIN_LAST_FPS]..[CAP_FPS], then the widest (lowest `first`); null keeps the HAL default. */
    fun pick(available: List<IntRange>): IntRange? = available
        .filter { it.last in MIN_LAST_FPS..CAP_FPS }
        .minWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.first })
}
