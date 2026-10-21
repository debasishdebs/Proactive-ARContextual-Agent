package com.rokid.context.glasses.capture.session

/** Picks the camera AE target fps range for the sampling stream: we analyse at most 3 fps, so stream at <= [CAP_FPS]. */
object FpsRangePick {
    const val CAP_FPS = 15
    private const val MIN_LAST_FPS = 5

    /** Lowest `last` within [MIN_LAST_FPS]..[CAP_FPS], then the widest (lowest `first`); null keeps the HAL default. */
    fun pick(available: List<IntRange>): IntRange? = available
        .filter { it.last in MIN_LAST_FPS..CAP_FPS }
        .minWithOrNull(compareBy<IntRange> { it.last }.thenBy { it.first })

    /** Reads once per process, but only a successful non-empty read is kept: a failed or empty one is retried on the next call. */
    class Cache(private val read: () -> List<IntRange>?) {
        var isResolved = false
            private set
        private var value: IntRange? = null

        fun get(): IntRange? {
            if (isResolved) return value
            val ranges = read()?.takeIf { it.isNotEmpty() } ?: return null
            value = pick(ranges)
            isResolved = true
            return value
        }
    }
}
