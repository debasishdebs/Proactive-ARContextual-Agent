package com.rokid.context.phone.ui.settings.privacy.zones

import android.content.SharedPreferences
import com.rokid.context.phone.place.SeedKeys

/** One seeded-area colour: a spoken name (the swatch's description) and an ARGB int. */
data class SeedColour(val name: String, val argb: Int)

/**
 * The palette a seeded area can be drawn in: 9 colours from the colour-blind-safe Okabe-Ito set plus dark neutrals, all distinct in lightness as well as hue. Pause zones stay red (0xFFD32F2F) and
 * pins purple (0xFF7B3FC4); no entry here is red, vermilion or purple, so a seeded area is never mistaken for them. Pure data.
 */
object SeedPalette {
    val colours: List<SeedColour> = listOf(
        SeedColour("blue", 0xFF0072B2.toInt()),
        SeedColour("orange", 0xFFE69F00.toInt()),
        SeedColour("green", 0xFF009E73.toInt()),
        SeedColour("pink", 0xFFCC79A7.toInt()),
        SeedColour("sky", 0xFF56B4E9.toInt()),
        SeedColour("brown", 0xFF8D6E63.toInt()),
        SeedColour("olive", 0xFF7A8B00.toInt()),
        SeedColour("navy", 0xFF283593.toInt()),
        SeedColour("grey", 0xFF607D8B.toInt()),
    )

    /** The default colour index of the area at [order]: the palette cycles, so neighbouring areas differ out of the box. */
    fun default(order: Int): Int = Math.floorMod(order, colours.size)

    /** The colour index to draw: the saved choice when it is a valid index, else the default for [order]. */
    fun resolve(saved: Int?, order: Int): Int = saved?.takeIf { it in colours.indices } ?: default(order)

    fun name(index: Int): String = colours[index.coerceIn(colours.indices)].name
    fun argb(index: Int): Int = colours[index.coerceIn(colours.indices)].argb
}

/**
 * The owner's colour choice per seeded area, in app-private prefs keyed by a hash of the area id (never the id, a name or a coordinate); the value is a palette index only.
 * Excluded from backup and device transfer (like place_seed_progress); removed with the area and by Delete all.
 */
class SeedColourStore(private val p: SharedPreferences) {
    private fun key(areaId: String) = "c." + SeedKeys.of(areaId)
    fun get(areaId: String): Int? = p.getInt(key(areaId), -1).takeIf { it in SeedPalette.colours.indices }
    fun set(areaId: String, index: Int) { if (index in SeedPalette.colours.indices) p.edit().putInt(key(areaId), index).apply() }
    fun clear(areaId: String) { p.edit().remove(key(areaId)).apply() }
    fun clearAll() { p.edit().clear().apply() }

    companion object {
        const val PREFS = "seed_area_colours"
        fun of(app: android.content.Context) = SeedColourStore(app.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE))
    }
}
