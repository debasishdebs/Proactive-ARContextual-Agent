package com.rokid.context.glasses.hud

import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Verbosity

/** Pages in swipe rotation (spec §3.4): Silent shows none (REC dot only); Max adds Status. */
fun pagesFor(v: Verbosity): List<HudPage> = when (v) {
    Verbosity.SILENT -> emptyList()
    Verbosity.MEDIUM -> listOf(HudPage.GLANCE, HudPage.TODAY)
    Verbosity.MAX -> listOf(HudPage.GLANCE, HudPage.TODAY, HudPage.STATUS)
}

/**
 * Page and Today-row state (spec §3.1 `HudNav`), adapted from Live Fit `HudNav`: next/prev page wraps through
 * [pagesFor]; on Today a long swipe moves the highlighted row; [IDLE_MS] without input returns to Glance.
 */
data class HudNav(val page: HudPage = HudPage.GLANCE, val todayRow: Int = 0, val lastInputMs: Long = 0) {
    fun onSwipeAction(action: GestureAction, long: Boolean, pages: List<HudPage>, todayRows: Int, nowMs: Long): HudNav {
        val pageAction = action == GestureAction.NEXT_PAGE || action == GestureAction.PREV_PAGE
        if (page == HudPage.TODAY && long && pageAction && todayRows > 0) {
            val d = if (action == GestureAction.NEXT_PAGE) 1 else -1
            return copy(todayRow = (todayRow + d).coerceIn(0, todayRows - 1), lastInputMs = nowMs)
        }
        return when (action) {
            GestureAction.NEXT_PAGE -> step(1, pages, nowMs)
            GestureAction.PREV_PAGE -> step(-1, pages, nowMs)
            else -> touched(nowMs)
        }
    }

    fun step(delta: Int, pages: List<HudPage>, nowMs: Long): HudNav {
        if (pages.isEmpty()) return touched(nowMs)
        val i = pages.indexOf(page).coerceAtLeast(0)
        val target = pages[Math.floorMod(i + delta, pages.size)]
        return if (target == page) touched(nowMs) else HudNav(page = target, lastInputMs = nowMs)
    }

    /** Any input restarts the idle timer. */
    fun touched(nowMs: Long): HudNav = copy(lastInputMs = nowMs)

    fun tick(nowMs: Long): HudNav =
        if (page != HudPage.GLANCE && nowMs - lastInputMs >= IDLE_MS) HudNav(lastInputMs = lastInputMs) else this

    /** The visible page left the rotation (verbosity changed) → Glance; a shorter Today list clamps the highlight. */
    fun reconcile(pages: List<HudPage>, todayRows: Int): HudNav = when {
        pages.isNotEmpty() && page !in pages -> HudNav(lastInputMs = lastInputMs)
        todayRows > 0 && todayRow >= todayRows -> copy(todayRow = todayRows - 1)
        else -> this
    }

    companion object { const val IDLE_MS = 6_000L }
}

/** The [rows] rows shown around [highlight] (centred where possible) out of [size] (from Live Fit `HudNav.kt`). */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}
