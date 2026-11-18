package com.rokid.context.core.hud.render

import com.rokid.context.core.model.PlaceSide

/** Where the place card sits: [widthDp] null = wrap content (the layout before B2); [insetDp] is the gap from the screen edge on [side]'s edge. */
data class PlaceCardBox(val side: PlaceSide, val widthDp: Float?, val insetDp: Float)

/**
 * Place Guide card placement (B2): pure layout math, no Compose.
 *
 * NOT MEASURED: the glasses' visible area / field of view was never measured. The display is 480x640 px at 240 dpi (240x320 dp, read with `wm size` / `wm density` on 2026-11-18),
 * but how much of it the wearer actually sees through the waveguide is unknown. [SAFE_MARGIN_DP] and [SIDE_WIDTH_FRACTION] are therefore deliberately conservative guesses: the final
 * side offsets must be tuned while WORN.
 */
object PlaceCardLayout {
    /** Gap kept clear on both screen edges for LEFT and RIGHT (unmeasured; conservative). */
    const val SAFE_MARGIN_DP = 24f
    /** A side card takes at most this fraction of the display width (leaves the other half of the view clear). */
    const val SIDE_WIDTH_FRACTION = 0.6f
    /** Narrowest a side card is made while the display allows it. */
    const val MIN_WIDTH_DP = 120f

    /**
     * CENTRE = [PlaceCardBox] with no width and no inset: the caller applies no modifier, so the card is exactly as before. LEFT and RIGHT: a card of [SIDE_WIDTH_FRACTION] of the display,
     * at least [MIN_WIDTH_DP] but never wider than the display minus both safe margins, [SAFE_MARGIN_DP] from its edge; on a display too small for the margins the margin shrinks to a
     * quarter of the width so the card is always fully inside it and never has a negative size.
     */
    fun compute(side: PlaceSide, displayWidthDp: Float): PlaceCardBox {
        if (side == PlaceSide.CENTRE || !displayWidthDp.isFinite() || displayWidthDp <= 0f) return PlaceCardBox(PlaceSide.CENTRE, null, 0f)
        val margin = minOf(SAFE_MARGIN_DP, displayWidthDp / 4f)
        val avail = displayWidthDp - 2f * margin
        val width = (displayWidthDp * SIDE_WIDTH_FRACTION).coerceAtLeast(MIN_WIDTH_DP).coerceAtMost(avail)
        return PlaceCardBox(side, width, margin)
    }
}
