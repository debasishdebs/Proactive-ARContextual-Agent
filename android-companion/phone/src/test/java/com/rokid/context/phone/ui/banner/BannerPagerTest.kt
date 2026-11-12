package com.rokid.context.phone.ui.banner

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BannerPagerTest {
    @get:Rule val compose = createComposeRule()
    private val actions = mutableListOf<BannerAction>()
    private val specs = mutableStateOf<List<BannerSpec>>(emptyList())

    /** AI_BLOCKED (Fix now), GLASSES_DISCONNECTED (Reconnect), CAPTURE_PAUSED (Resume), most severe first. */
    private val three = BannerInputs(aiBlockedText = "no key", link = LinkState.OFFLINE, pipelineEnabled = false)
    private fun cands(i: BannerInputs) = BannerResolver.candidates(i)

    private fun show(list: List<BannerSpec>) {
        specs.value = list
        compose.setContent { PcTheme { BannerPager(specs.value, onAction = { actions += it }) } }
    }

    private fun dots() = compose.onAllNodesWithTag(BannerPagerTags.DOTS)
    private fun activeDot(i: Int, n: Int) {
        for (d in 0 until n) {
            if (d == i) compose.onNodeWithTag(BannerPagerTags.dot(d, true)).assertIsDisplayed()
            else compose.onNodeWithTag(BannerPagerTags.dot(d, false)).assertIsDisplayed()
        }
    }
    private fun swipeToNext() { compose.onNodeWithTag(BannerPagerTags.PAGER).performTouchInput { swipeLeft() }; compose.waitForIdle() }
    private fun swipeToPrevious() { compose.onNodeWithTag(BannerPagerTags.PAGER).performTouchInput { swipeRight() }; compose.waitForIdle() }

    @Test fun noBannerRendersNothing() {
        show(emptyList())
        dots().assertCountEquals(0)
        compose.onAllNodesWithTag(BannerPagerTags.PAGER).assertCountEquals(0)
    }

    @Test fun oneBannerHasNoDotsAndNoPagerAnnouncement() {
        show(cands(BannerInputs(link = LinkState.OFFLINE)))
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        dots().assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Banner 1 of 1").assertCountEquals(0)
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(listOf(BannerAction.RECONNECT), actions)
    }

    @Test fun threeBannersShowThreeDotsAndSwipingMovesTheHighlight() {
        show(cands(three))
        dots().assertCountEquals(1)
        compose.onNodeWithText("Fix now").assertIsDisplayed()           // first page = most severe
        activeDot(0, 3)
        compose.onNodeWithContentDescription("Banner 1 of 3").assertExists()
        swipeToNext()
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        activeDot(1, 3)
        compose.onNodeWithContentDescription("Banner 2 of 3").assertExists()
        swipeToNext()
        compose.onNodeWithText("Capture paused").assertIsDisplayed()
        activeDot(2, 3)
        swipeToPrevious()
        activeDot(1, 3)
    }

    @Test fun measureCopiesAreNotExposedAsExtraNodes() {
        show(cands(three))
        compose.onAllNodesWithText("Fix now").assertCountEquals(1)
        compose.onAllNodesWithText("Reconnect").assertCountEquals(0) // page 2 is not composed yet, and its measure copy is hidden
    }

    @Test fun actionButtonOnPageTwoFiresItsAction() {
        show(cands(three))
        swipeToNext()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(listOf(BannerAction.RECONNECT), actions)
        swipeToNext()
        compose.onNodeWithText("Resume").performClick()
        assertEquals(listOf(BannerAction.RECONNECT, BannerAction.RESUME), actions)
    }

    @Test fun shrinkingKeepsTheUserOnTheSameBanner() {
        show(cands(three))
        swipeToNext()
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        compose.runOnIdle { specs.value = cands(three.copy(aiBlockedText = null)) } // the page above goes away
        compose.waitForIdle()
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        activeDot(0, 2)
    }

    @Test fun shrinkingTheCurrentPageClamps() {
        show(cands(three))
        swipeToNext(); swipeToNext()
        compose.onNodeWithText("Capture paused").assertIsDisplayed()
        compose.runOnIdle { specs.value = cands(three.copy(pipelineEnabled = true)) } // the last page goes away
        compose.waitForIdle()
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        activeDot(1, 2)
        compose.runOnIdle { specs.value = cands(BannerInputs(aiBlockedText = "no key")) } // down to one: no dots
        compose.waitForIdle()
        compose.onNodeWithText("Fix now").assertIsDisplayed()
        dots().assertCountEquals(0)
        compose.runOnIdle { specs.value = emptyList() }
        compose.waitForIdle()
        compose.onAllNodesWithTag(BannerPagerTags.PAGER).assertCountEquals(0)
    }

    @Test fun aMoreSevereBannerDoesNotYankTheUserAway() {
        show(cands(three.copy(aiBlockedText = null)))                   // GLASSES, CAPTURE_PAUSED; user on GLASSES
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        compose.runOnIdle { specs.value = cands(three) }                // AI_BLOCKED appears above
        compose.waitForIdle()
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        compose.onAllNodesWithText("Fix now").assertCountEquals(0)
        activeDot(1, 3)                                                 // the dots show the new one
        swipeToPrevious()
        compose.onNodeWithText("Fix now").assertIsDisplayed()
    }

    @Test fun aNewBannerFromASingleOneKeepsTheUserOnIt() {
        show(cands(BannerInputs(link = LinkState.OFFLINE)))
        compose.runOnIdle { specs.value = cands(BannerInputs(aiBlockedText = "x", link = LinkState.OFFLINE)) }
        compose.waitForIdle()
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        activeDot(1, 2)
    }

    @Test fun theCurrentBannerDisappearingMovesToTheNearestOne() {
        show(cands(three))
        swipeToNext()
        compose.runOnIdle { specs.value = cands(three.copy(link = LinkState.CONNECTED)) } // GLASSES gone: AI_BLOCKED, CAPTURE_PAUSED
        compose.waitForIdle()
        compose.onNodeWithText("Capture paused").assertIsDisplayed()
        activeDot(1, 2)
    }
}
