package com.rokid.context.phone.ui.settings.mapzones

import kotlin.math.max

// Map zones P1 (flag mapZonesV2): pure logic for the touch arbiter and for TalkBack. No Compose, no Android, no logging. Nothing here holds a name or a coordinate.

/** What one touch is turning into. */
enum class TouchDecision { WAIT, TAP, PAN, DRAW }

/**
 * Decides, from facts about ONE gesture that began on empty map, whether it is still undecided, a tap, a pan / pinch, or a long-press draw. The order is the point:
 * a second finger always means pan / pinch (it can never become a draw); a lift before the long-press time is a tap; moving past the touch slop is a pan (a short drag pans); only a single finger that has stayed within the
 * slop for the whole long-press time starts drawing.
 */
object TouchArbiter {
    fun decide(pressedCount: Int, firstFingerUp: Boolean, movedPx: Float, slopPx: Float, elapsedMs: Long, longPressMs: Long): TouchDecision = when {
        pressedCount >= 2 -> TouchDecision.PAN
        firstFingerUp -> TouchDecision.TAP
        movedPx > slopPx -> TouchDecision.PAN
        elapsedMs >= longPressMs -> TouchDecision.DRAW
        else -> TouchDecision.WAIT
    }
}

enum class StepDir { NORTH, SOUTH, EAST, WEST }

/** Step actions for the draft and selection cycling, for the TalkBack custom actions (the same effect as dragging a handle, without a gesture). */
object ShapeSteps {
    const val MOVE_FRACTION = 0.25
    const val GROW = 1.25

    /** Moves the shape by a quarter of its own size in [dir]; unchanged when that would break it. */
    fun move(s: DrawnShape, dir: StepDir): DrawnShape {
        val b = ShapeEdit.box(s) ?: return s
        val dLat = max((b[2] - b[0]) * MOVE_FRACTION, ShapeEdit.MIN_SPAN_DEG / 2)
        val dLon = max((b[3] - b[1]) * MOVE_FRACTION, ShapeEdit.MIN_SPAN_DEG / 2)
        return when (dir) {
            StepDir.NORTH -> ShapeEdit.move(s, dLat, 0.0)
            StepDir.SOUTH -> ShapeEdit.move(s, -dLat, 0.0)
            StepDir.EAST -> ShapeEdit.move(s, 0.0, dLon)
            StepDir.WEST -> ShapeEdit.move(s, 0.0, -dLon)
        }
    }

    /** Scales the shape about its centre by [GROW] (or its inverse). Unchanged when it would pass the size limits or leave the map. */
    fun resize(s: DrawnShape, grow: Boolean): DrawnShape {
        val f = if (grow) GROW else 1 / GROW
        return when (s) {
            is DrawnShape.Circle -> {
                val r = s.radiusM * f
                if (r < ShapeEdit.MIN_RADIUS_M || r > ShapeEdit.MAX_RADIUS_M) s else s.copy(radiusM = r).takeIf { ShapeEdit.box(it) != null } ?: s
            }
            is DrawnShape.Rect -> {
                val cy = (s.south + s.north) / 2; val cx = (s.west + s.east) / 2
                val hh = (s.north - s.south) / 2 * f; val hw = (s.east - s.west) / 2 * f
                if (hh * 2 < ShapeEdit.MIN_SPAN_DEG || hw * 2 < ShapeEdit.MIN_SPAN_DEG) return s
                val next = DrawnShape.Rect(cy - hh, cx - hw, cy + hh, cx + hw)
                if (ShapeEdit.box(next) != null && next.south >= -85.0 && next.north <= 85.0 && next.west >= -180.0 && next.east <= 180.0) next else s
            }
        }
    }

    private fun ordered(items: List<MapItem>, layers: Set<MapLayer>): List<MapItem> =
        items.filter { it.type != ItemType.PIN && MapOverview.layerOf(it.type) in layers }.sortedWith(compareBy({ it.type != ItemType.PAUSE }, { it.id }))

    private fun selOf(i: MapItem) = Selection(if (i.type == ItemType.PAUSE) SelectionKind.PAUSE else SelectionKind.SEED, i.id)

    /** The next zone or seed area after [current] (pause zones first, then seed areas, each by id), wrapping round; the first when nothing is selected; null when there is none. Pins are not selectable. */
    fun nextSelection(items: List<MapItem>, current: Selection?, layers: Set<MapLayer>): Selection? {
        val order = ordered(items, layers).map { selOf(it) }
        if (order.isEmpty()) return null
        return order[(order.indexOf(current) + 1) % order.size]
    }

    /** "pause zone 2 of 3": the selected item's place in the same order, never its name or position. */
    fun position(items: List<MapItem>, sel: Selection?, layers: Set<MapLayer>): String? {
        sel ?: return null
        val order = ordered(items, layers)
        val i = order.indexOfFirst { selOf(it) == sel }
        if (i < 0) return null
        return (if (sel.kind == SelectionKind.PAUSE) "pause zone" else "seed area") + " ${i + 1} of ${order.size}"
    }
}

/** The spoken text of the map for TalkBack. It has counts, sizes and places in the list; it never has a zone name or a coordinate. */
object MapSpeech {
    fun describe(summary: String, shape: DrawnShape?, selectedPosition: String?): String = buildString {
        append(summary)
        if (shape != null) append(". Drawing a ").append(if (shape.kind == ShapeKind.RECT) "rectangle" else "circle").append(", ").append(ShapeEdit.sizeText(shape))
        if (selectedPosition != null) append(". Selected ").append(selectedPosition)
    }
}
