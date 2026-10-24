package com.rokid.context.canvas.moment

/**
 * A Moment id is the lowercase hex of the phone-clock epoch second, zero-padded to 8 digits (moment-model spec 3.1): a pure function of the second, so there is
 * nothing to store and fixed width makes lexicographic order chronological. Seconds outside `0..0xFFFFFFFF` (before 1970, after 2106-02-07) have no Moment.
 */
object MomentId {
    const val MAX_SECOND = 0xFFFFFFFFL
    private val SHAPE = Regex("[0-9a-f]{8}")

    /** The id of the second that holds [wallMs]. Throws [IllegalArgumentException] outside the 8-digit range. */
    fun ofMs(wallMs: Long): String = format(Math.floorDiv(wallMs, 1000L))

    fun format(second: Long): String {
        require(second in 0..MAX_SECOND) { "moment second out of range" }
        return "%08x".format(second)
    }

    /** The second of [id], or null when [id] is not exactly 8 lowercase hex digits. */
    fun parse(id: String): Long? = if (SHAPE.matches(id)) id.toLong(16) else null

    fun startMs(id: String): Long? = parse(id)?.times(1000)
}
