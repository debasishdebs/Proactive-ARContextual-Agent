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

    /**
     * A device-info snapshot is meaningful only while the glasses' wear check is on (device-found: with the check off
     * the SDK reports "worn" for glasses lying on a desk). Check not confirmed on -> ignore it (null); on -> apply the
     * parsed value (null if unparseable).
     */
    fun snapshotToApply(parsed: Boolean?, wearCheckOn: Boolean?): Boolean? = if (wearCheckOn == true) parsed else null

    /** Log-safe view of the raw SDK string: one token from a fixed list (worn, not_worn, unknown, off, other), never the text itself. */
    fun describe(raw: String?): String {
        val v = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace('-', '_') ?: return "unknown"
        return when {
            v == "off" -> "off"
            v == "unknown" -> "unknown"
            else -> when (parse(raw)) { true -> "worn"; false -> "not_worn"; null -> "other" }
        }
    }
}
