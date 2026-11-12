package com.rokid.context.phone.ui.banner

/** Page-keeping rule for the Home banner pager (ruling 2026-11-13); pure so it is unit-tested without Compose. */
object BannerPaging {
    /**
     * The page to show after the banner list became [kinds] (severity order): the page of [anchorKind] (the banner the user was on)
     * if it still exists, so a banner appearing above it never moves the user; otherwise [anchorPage] clamped to the new list.
     * Always a valid index (0 for an empty list).
     */
    fun pageAfterChange(anchorKind: BannerKind?, anchorPage: Int, kinds: List<BannerKind>): Int {
        if (kinds.isEmpty()) return 0
        val same = if (anchorKind == null) -1 else kinds.indexOf(anchorKind)
        return if (same >= 0) same else anchorPage.coerceIn(0, kinds.lastIndex)
    }

    /** "Banner 2 of 3": the pager's spoken position; only meaningful with two or more banners. */
    fun announce(page: Int, count: Int): String = "Banner ${page + 1} of $count"
}
