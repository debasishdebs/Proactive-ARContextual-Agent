package com.rokid.context.core.model.zone

import kotlinx.serialization.Serializable

/**
 * A red pause zone (spec 2026-11-16 §B2): an axis-aligned lat/lon rectangle plus a buffer. Capture is paused while the phone is inside the rectangle grown by
 * [bufferM] (see [ZoneEvaluator]). User configuration: stored on the phone, exported with the settings, never deleted by Delete all. The box is NOT a log-safe value:
 * logs name a zone by [id] only.
 */
@Serializable
data class PauseZone(
    /** Stable id without `:` (a preset id, or generated); it is part of every pause record id (`zone:<id>:<n>`). */
    val id: String,
    val name: String,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val bufferM: Int = DEFAULT_BUFFER_M,
    val enabled: Boolean = true,
    /** A [ZonePresets] id this zone was made from; null = drawn by the user. */
    val preset: String? = null,
) {
    /** True when the id, the box and the buffer are usable; an invalid zone is never stored. */
    fun isValid(): Boolean =
        id.isNotBlank() && ':' !in id && name.isNotBlank() &&
            south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite() &&
            south in -90.0..90.0 && north in -90.0..90.0 && west in -180.0..180.0 && east in -180.0..180.0 &&
            south < north && west < east && bufferM in 0..MAX_BUFFER_M

    companion object {
        const val DEFAULT_BUFFER_M = 300
        const val MAX_BUFFER_M = 50_000
    }
}

/** The pause record ids of zones: `zone:<zoneId>:<n>`. They are phone-owned (origin PHONE); only the zone logic ends them. */
object ZonePause {
    const val PREFIX = "zone:"

    fun pauseId(zoneId: String, n: Long): String = "$PREFIX$zoneId:$n"

    fun isZoneRecord(pauseId: String): Boolean = pauseId.startsWith(PREFIX)

    /** The zone id of a zone record id, or null for any other id. */
    fun zoneIdOf(pauseId: String): String? =
        if (!isZoneRecord(pauseId) || pauseId.count { it == ':' } < 2) null else pauseId.removePrefix(PREFIX).substringBeforeLast(':').takeIf { it.isNotEmpty() }
}

object ZonePresets {
    const val JARAWA_ID = "jarawa"

    /**
     * The Andaman Trunk Road through the Jarawa reserve, Jirkatang check post to Middle Strait. **UNVERIFIED, APPROXIMATE**: the box (about 11.80-12.15 N, 92.60-92.85 E)
     * was typed from memory of the road, not measured; the user checks and adjusts it on the map before use. Photographing the Jarawa is illegal, so the buffer is 5 km and the
     * preset is on by default once added.
     */
    val JARAWA = PauseZone(
        id = JARAWA_ID, name = "Jarawa reserve (Andaman Trunk Road)",
        south = 11.80, west = 92.60, north = 12.15, east = 92.85,
        bufferM = 5_000, enabled = true, preset = JARAWA_ID,
    )

    val ALL: List<PauseZone> = listOf(JARAWA)
}

/** What survives a process death about one zone: the latched state, when a fix last proved us clear of it (WALL clock ms) and when the last fix of any kind arrived (wall ms). Never a coordinate. */
@Serializable
data class ZoneMemo(val state: String = "INSIDE", val clearWallMs: Long? = null, val lastFixWallMs: Long? = null)
