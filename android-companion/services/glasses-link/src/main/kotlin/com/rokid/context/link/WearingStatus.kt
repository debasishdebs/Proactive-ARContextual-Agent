package com.rokid.context.link

/**
 * Fix wave I1: `GlassInfo.wearingStatus` is a String in the SDK jar (no documented values), so it is read defensively:
 * only a clear "worn" or "not worn" word is a verdict, anything else is unknown (null, never a guess).
 */
object WearingStatus {
    private val WORN = setOf("1", "true", "worn", "wear", "wearing", "on", "on_head", "wearing_on")
    private val NOT_WORN = setOf("0", "false", "not_worn", "notworn", "unworn", "not_wearing", "off", "off_head", "take_off", "removed", "wearing_off")

    fun parse(raw: String?): Boolean? {
        val v = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') ?: return null
        return when (v) {
            in WORN -> true
            in NOT_WORN -> false
            else -> null
        }
    }
}
