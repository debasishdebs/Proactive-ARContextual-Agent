package com.rokid.context.phone.ui.banner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.rokid.context.core.ui.theme.PcColors
import kotlinx.coroutines.flow.filter

/** Test tags of [BannerPager]: dot `i` is tagged `banner_dot_i`, plus `_active` on the current one. */
object BannerPagerTags {
    const val PAGER = "banner_pager"
    const val DOTS = "banner_dots"
    fun dot(i: Int, active: Boolean) = "banner_dot_$i" + if (active) "_active" else ""
}

/**
 * Home's banners (ruling 2026-11-13): every active banner as a swipeable tile (most severe first), one page-indicator dot per banner
 * below, no auto-scroll. Nothing for no banner; one banner is a plain tile with no dots. Each tile is [BannerHost], unchanged. Tiles share the tallest tile's
 * height so the page does not jump while swiping. The page follows the banner the user is on (by [BannerKind]) when the list changes
 * ([BannerPaging.pageAfterChange]).
 */
@Composable
fun BannerPager(specs: List<BannerSpec>, onAction: (BannerAction) -> Unit, modifier: Modifier = Modifier) {
    if (specs.isEmpty()) return
    val kinds = specs.map { it.kind }
    val state = rememberPagerState { specs.size }
    KeepBanner(state, kinds)
    val latest by rememberUpdatedState(specs)
    val page = state.currentPage.coerceIn(0, specs.lastIndex)
    Column(modifier) {
        val spoken = if (specs.size > 1) Modifier.semantics {
            // A polite live region: TalkBack says the new position after a swipe. The tiles (and their buttons) stay separate nodes.
            contentDescription = BannerPaging.announce(page, specs.size)
            liveRegion = LiveRegionMode.Polite
        } else Modifier
        EqualHeight(specs, Modifier.testTag(BannerPagerTags.PAGER).then(spoken)) { h ->
            HorizontalPager(state, Modifier.fillMaxWidth().height(h), key = { i -> latest.getOrNull(i)?.kind ?: i }) { i ->
                latest.getOrNull(i)?.let { BannerHost(it, onAction) }
            }
        }
        if (specs.size > 1) Dots(page, specs.size)
    }
}

/** Remembers the banner the user settled on and, when [kinds] changes, returns to it (or the nearest valid page). Never throws on a shrunk list. */
@Composable
private fun KeepBanner(state: PagerState, kinds: List<BannerKind>) {
    val latestKinds by rememberUpdatedState(kinds)
    val anchor = remember { Anchor() }
    // Only a finished scroll (a swipe, TalkBack's scroll action, or our own restore) moves the anchor; the pager clamping itself does not.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.filter { !it }.collect {
            val k = latestKinds
            val p = state.currentPage.coerceIn(0, (k.size - 1).coerceAtLeast(0))
            k.getOrNull(p)?.let { anchor.kind = it; anchor.page = p }
        }
    }
    LaunchedEffect(kinds) {
        val target = BannerPaging.pageAfterChange(anchor.kind, anchor.page, kinds)
        anchor.kind = kinds.getOrNull(target); anchor.page = target
        if (state.currentPage != target) state.scrollToPage(target)
    }
}

private class Anchor { var kind: BannerKind? = null; var page: Int = 0 }

/** Measures every tile at the available width, then gives [content] the tallest height (same tiles, hidden from accessibility). */
@Composable
private fun EqualHeight(specs: List<BannerSpec>, modifier: Modifier, content: @Composable (androidx.compose.ui.unit.Dp) -> Unit) {
    SubcomposeLayout(modifier) { c ->
        val tallest = specs.maxOf { s ->
            subcompose("measure-${s.kind}") { BannerHost(s, {}, Modifier.clearAndSetSemantics {}) }
                .maxOf { it.measure(Constraints(maxWidth = c.maxWidth)).height }
        }
        val placeables = subcompose("pager") { content(tallest.toDp()) }.map { it.measure(Constraints.fixedWidth(c.maxWidth)) }
        val h = placeables.maxOfOrNull { it.height } ?: 0
        layout(c.maxWidth, h) { placeables.forEach { it.place(0, 0) } }
    }
}

/** 8 dp dots, 6 dp apart: the current one solid mint-deep, the others a faint soft-ink. No semantics: TalkBack skips them (the pager speaks its position). */
@Composable
private fun Dots(current: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp).testTag(BannerPagerTags.DOTS),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            Row(
                Modifier.size(if (active) 8.dp else 6.dp).clip(CircleShape)
                    .background(if (active) PcColors.MintDeep else PcColors.InkSoft.copy(alpha = 0.35f))
                    .testTag(BannerPagerTags.dot(i, active)),
            ) {}
        }
    }
}
