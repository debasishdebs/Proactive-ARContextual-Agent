package com.rokid.context.phone.ui.banner

import com.rokid.context.phone.ui.banner.BannerKind.AI_BLOCKED
import com.rokid.context.phone.ui.banner.BannerKind.CAPTURE_PAUSED
import com.rokid.context.phone.ui.banner.BannerKind.GLASSES_DISCONNECTED
import com.rokid.context.phone.ui.banner.BannerKind.STORAGE
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerPagingTest {
    private fun page(kind: BannerKind?, page: Int, vararg now: BannerKind) = BannerPaging.pageAfterChange(kind, page, now.toList())

    @Test fun staysOnTheSameBannerWhenItMovesDown() {
        // A more severe banner appears above the one the user is on: no yank, the index just shifts.
        assertEquals(1, page(GLASSES_DISCONNECTED, 0, AI_BLOCKED, GLASSES_DISCONNECTED, CAPTURE_PAUSED))
    }

    @Test fun staysOnTheSameBannerWhenItMovesUp() {
        assertEquals(0, page(GLASSES_DISCONNECTED, 1, GLASSES_DISCONNECTED, CAPTURE_PAUSED))
        assertEquals(1, page(CAPTURE_PAUSED, 2, GLASSES_DISCONNECTED, CAPTURE_PAUSED))
    }

    @Test fun unchangedListKeepsThePage() {
        assertEquals(2, page(STORAGE, 2, AI_BLOCKED, GLASSES_DISCONNECTED, STORAGE))
    }

    @Test fun vanishedBannerClampsToAValidPage() {
        assertEquals(1, page(STORAGE, 2, AI_BLOCKED, GLASSES_DISCONNECTED))   // last page gone -> last page
        assertEquals(1, page(CAPTURE_PAUSED, 1, AI_BLOCKED, STORAGE))          // middle gone -> same slot (the next banner)
        assertEquals(0, page(AI_BLOCKED, 0, GLASSES_DISCONNECTED, STORAGE))    // first gone -> first
    }

    @Test fun neverOutOfRange() {
        assertEquals(0, page(STORAGE, 5))                                      // empty list
        assertEquals(0, page(null, 3, AI_BLOCKED))                             // no anchor yet, stale index
        assertEquals(0, page(null, -2, AI_BLOCKED, STORAGE))
        assertEquals(1, page(null, 9, AI_BLOCKED, STORAGE))
        assertEquals(0, page(null, 0, AI_BLOCKED, STORAGE))
    }

    @Test fun announcesOneBasedPosition() {
        assertEquals("Banner 1 of 3", BannerPaging.announce(0, 3))
        assertEquals("Banner 3 of 3", BannerPaging.announce(2, 3))
    }
}
