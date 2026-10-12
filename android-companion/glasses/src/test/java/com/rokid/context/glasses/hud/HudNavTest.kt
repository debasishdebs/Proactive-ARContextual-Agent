package com.rokid.context.glasses.hud

import com.rokid.context.core.model.GestureAction
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.Verbosity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudNavTest {
    private val medium = pagesFor(Verbosity.MEDIUM)
    private val max = pagesFor(Verbosity.MAX)

    @Test fun pagesPerVerbosity() {
        assertTrue(pagesFor(Verbosity.SILENT).isEmpty())
        assertEquals(listOf(HudPage.GLANCE, HudPage.TODAY), medium)
        assertEquals(listOf(HudPage.GLANCE, HudPage.TODAY, HudPage.STATUS), max)
    }

    @Test fun startsOnGlanceAndWraps() {
        val n = HudNav()
        assertEquals(HudPage.GLANCE, n.page)
        assertEquals(HudPage.TODAY, n.step(1, medium, 0).page)
        assertEquals(HudPage.GLANCE, n.step(1, medium, 0).step(1, medium, 0).page)
        assertEquals(HudPage.STATUS, n.step(-1, max, 0).page)
    }

    @Test fun longSwipeOnTodayMovesTheHighlightAndClamps() {
        val today = HudNav(page = HudPage.TODAY)
        assertEquals(1, today.onSwipeAction(GestureAction.NEXT_PAGE, long = true, medium, todayRows = 3, nowMs = 1).todayRow)
        assertEquals(0, today.onSwipeAction(GestureAction.PREV_PAGE, long = true, medium, todayRows = 3, nowMs = 1).todayRow)
        assertEquals(2, HudNav(HudPage.TODAY, todayRow = 2).onSwipeAction(GestureAction.NEXT_PAGE, long = true, medium, 3, 1).todayRow)
        assertEquals(HudPage.TODAY, today.onSwipeAction(GestureAction.NEXT_PAGE, long = true, medium, 3, 1).page)
    }

    @Test fun shortSwipeOnTodayChangesPage() =
        assertEquals(HudPage.GLANCE, HudNav(page = HudPage.TODAY).onSwipeAction(GestureAction.NEXT_PAGE, long = false, medium, 3, 1).page)

    /** Spec §3.1: 6 s idle → Glance. */
    @Test fun idleReturnsToGlanceAfterSixSeconds() {
        val n = HudNav(page = HudPage.TODAY, todayRow = 2, lastInputMs = 1_000)
        assertEquals(HudPage.TODAY, n.tick(6_999).page)
        assertEquals(HudNav(lastInputMs = 1_000), n.tick(7_000))
    }

    @Test fun anyInputRestartsTheIdleTimer() = assertEquals(HudPage.TODAY, HudNav(page = HudPage.TODAY, lastInputMs = 0).touched(5_000).tick(10_000).page)

    @Test fun droppingToMediumLeavesStatus() = assertEquals(HudPage.GLANCE, HudNav(page = HudPage.STATUS).reconcile(medium, todayRows = 0).page)

    @Test fun aShorterTodayListClampsTheHighlight() = assertEquals(1, HudNav(HudPage.TODAY, todayRow = 5).reconcile(medium, todayRows = 2).todayRow)

    @Test fun visibleRowsCentreTheHighlight() {
        assertEquals(0 until 4, visibleRows(size = 8, highlight = 0, rows = 4))
        assertEquals(3 until 7, visibleRows(size = 8, highlight = 5, rows = 4))
        assertEquals(4 until 8, visibleRows(size = 8, highlight = 7, rows = 4))
        assertTrue(visibleRows(size = 0, highlight = 0, rows = 4).isEmpty())
    }
}
