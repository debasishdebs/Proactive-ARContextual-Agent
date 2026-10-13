package com.rokid.context.phone.ui.nudge

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ReviewState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class NudgeDetailTest {
    @get:Rule val compose = createComposeRule()

    private fun mem(id: String, text: String, state: ReviewState = ReviewState.OK) =
        MemoryEntity(id, null, text, "fact", "", MemoryTier.SHORT, 0.8, state, true, 1, false, "")

    @Test fun unknownIdShowsGone() {
        var backs = 0
        compose.setContent { PcTheme { NudgeDetailScreen(NudgeDetailUiState.GONE, onBack = { backs++ }, onOpenMemory = {}) } }
        compose.onNodeWithText("This nudge is gone").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun showsTextWhyAndOnlyLiveSources() {
        val n = NudgeEntity("n1", 1, "Milk is nearly out", "You saw an empty carton", "[\"m1\",\"m2\",\"m3\"]", 1, "h", "glance", false)
        val state = NudgeDetailModel.build(n, listOf(mem("m1", "Milk carton nearly empty"), mem("m2", "forgotten fact", ReviewState.INVALIDATED)), emptyList(), ZoneId.of("UTC"))
        assertEquals(listOf("m1"), state.sources.map { it.memoryId })
        compose.setContent { PcTheme { NudgeDetailScreen(state, onBack = {}, onOpenMemory = {}) } }
        compose.onNodeWithText("Heads up").assertIsDisplayed()
        compose.onNodeWithText("Milk is nearly out").assertIsDisplayed()
        compose.onNodeWithText("You saw an empty carton").assertIsDisplayed()
        compose.onNodeWithText("Milk carton nearly empty").assertIsDisplayed()
        compose.onAllNodesWithText("forgotten fact").assertCountEquals(0)
    }

    @Test fun aRedactedNudgeShowsNoText() {
        val n = NudgeEntity("n1", 1, "secret", "secret why", "[\"m1\"]", 1, "h", "glance", true)
        val state = NudgeDetailModel.build(n, listOf(mem("m1", "a fact")), emptyList(), ZoneId.of("UTC"))
        compose.setContent { PcTheme { NudgeDetailScreen(state, onBack = {}, onOpenMemory = {}) } }
        compose.onNodeWithText("Removed \u2014 a source was forgotten").assertIsDisplayed()
        compose.onAllNodesWithText("secret").assertCountEquals(0)
        compose.onAllNodesWithText("a fact").assertCountEquals(0)
    }
}
