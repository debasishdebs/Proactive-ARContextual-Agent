package com.rokid.context.phone.ui.ask

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AskDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ratings = mutableListOf<Boolean>()
    private val opened = mutableListOf<String>()

    private fun show(state: AskDetailUiState) = compose.setContent {
        PcTheme { AskDetailScreen(state, onBack = {}, onRate = { ratings += it }, onOpenMemory = { opened += it }) }
    }

    private val found = AskDetailUiState(
        loading = false, found = true, question = "Where are my keys?", answer = "On the hallway shelf.", timeLabel = "Mon 12 Oct · 09:41",
        rating = 1, sources = listOf(SourceCard("m1", "Keys on hallway shelf", 0.71, null)), missingSources = 1,
        proposal = ProposalLine("Reminder", "Waiting for your answer"),
    )

    @Test fun showsAnswerSourcesAndActionAndOpensAMemory() {
        show(found)
        compose.onNodeWithText("Where are my keys?").assertIsDisplayed()
        compose.onNodeWithText("On the hallway shelf.").assertIsDisplayed()
        compose.onNodeWithText("71%").assertIsDisplayed()
        compose.onNodeWithText("1 source is no longer available").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reminder · Waiting for your answer").assertIsDisplayed()
        compose.onNodeWithText("Keys on hallway shelf").performScrollTo().performClick()
        assertEquals(listOf("m1"), opened)
    }

    @Test fun ratingButtonsReportAndShowSelection() {
        show(found)
        compose.onNodeWithContentDescription("Helpful").assertIsSelected()
        compose.onNodeWithContentDescription("Not helpful").performClick()
        assertEquals(listOf(false), ratings)
    }

    @Test fun redactedAnswerHasNoRating() {
        show(found.copy(redacted = true, answer = "Removed — a source was forgotten", sources = emptyList(), missingSources = 0))
        compose.onNodeWithText("Removed — a source was forgotten").assertIsDisplayed()
        compose.onNodeWithContentDescription("Helpful").assertDoesNotExist()
    }

    @Test fun unknownAskShowsNotFound() {
        show(AskDetailModel.NOT_FOUND)
        compose.onNodeWithText("This answer is no longer available").assertIsDisplayed()
    }
}
