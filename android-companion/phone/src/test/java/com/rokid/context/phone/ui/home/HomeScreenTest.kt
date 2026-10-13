package com.rokid.context.phone.ui.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerInputs
import com.rokid.context.phone.ui.banner.BannerResolver
import com.rokid.context.phone.ui.status.StatusUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val asked = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var pauses = 0
    private var banner: BannerAction? = null

    private fun show(state: HomeUiState) = compose.setContent {
        PcTheme { HomeScreen(state, onQuestionChange = {}, onAsk = { asked += it }, onOpenAsk = { opened += it }, onTogglePause = { pauses++ }, onBannerAction = { banner = it }) }
    }

    private val live = StatusUi("Capturing · next 4:12", live = true, paused = false, batteryPct = 82, banner = null)

    @Test fun headerShowsCaptureLineAndPauseToggles() {
        show(HomeUiState(status = live))
        compose.onNodeWithText("Proactive Context").assertIsDisplayed()
        compose.onNodeWithText("● Capturing · next 4:12").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause capture").performClick()
        assertEquals(1, pauses)
    }

    @Test fun pausedHeaderOffersResumeAndShowsBanner() {
        show(HomeUiState(status = StatusUi("Paused", false, true, null, BannerResolver.resolve(BannerInputs(link = LinkState.OFFLINE)), pipelineEnabled = false)))
        compose.onNodeWithText("❚❚ Paused").assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume capture").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(BannerAction.RECONNECT, banner)
    }

    /** Final review I1: a glasses pause (pipeline on) offers no resume that would switch the pipeline off. */
    @Test fun glassesPauseShowsANonActionablePausedOnGlassesState() {
        show(HomeUiState(status = StatusUi("Paused on glasses", false, true, null, null, pipelineEnabled = true, pausedOnGlasses = true)))
        compose.onNodeWithText("❚❚ Paused on glasses").assertIsDisplayed()
        compose.onNodeWithContentDescription("Paused on glasses").assertIsNotEnabled()
        compose.onAllNodesWithContentDescription("Resume capture").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Pause capture").assertCountEquals(0)
        assertEquals(0, pauses)
    }

    @Test fun suggestionChipsAskImmediatelyAndMicIsDisabled() {
        show(HomeUiState(status = live, suggestions = listOf("What about milk?", "Where did I leave my keys?", "What did I eat today?")))
        compose.onNodeWithText("Ask about your day").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask by voice").assertIsNotEnabled()
        compose.onNodeWithText("What about milk?").performClick()
        assertEquals(listOf("What about milk?"), asked)
    }

    @Test fun typedQuestionIsSentAndErrorsShow() {
        show(HomeUiState(status = live, question = "where are my keys", askError = "AI unavailable — check Settings › Model endpoints"))
        compose.onNodeWithContentDescription("Send question").performClick()
        assertEquals(listOf("where are my keys"), asked)
        compose.onNodeWithText("AI unavailable — check Settings › Model endpoints").assertIsDisplayed()
    }

    @Test fun feedShowsAsksAndNudgesAndOpensAnAsk() {
        val feed = listOf(
            FeedItem.Nudge("n1", 2, "09:41", "Milk looked almost empty", "Seen in the fridge at 09:41", redacted = false),
            FeedItem.Ask("a1", 1, "08:15", "Did I take my vitamins?", "Yes, at 07:52.", redacted = false),
        )
        show(HomeUiState(status = live, feed = feed))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("You asked “Did I take my vitamins?”"))
        compose.onNodeWithText("You asked “Did I take my vitamins?”").performClick()
        assertEquals(listOf("a1"), opened)
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Milk looked almost empty"))
        compose.onNodeWithText("Milk looked almost empty").performClick()
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Seen in the fridge at 09:41"))
        compose.onNodeWithText("Seen in the fridge at 09:41").assertIsDisplayed()
    }

    @Test fun emptyFeedShowsHint() {
        show(HomeUiState(status = live))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Answers and heads-ups will appear here."))
        compose.onNodeWithText("Answers and heads-ups will appear here.").assertIsDisplayed()
    }

    @Test fun inlineAnswerOpensItsDetail() {
        show(HomeUiState(status = live, lastAnswer = FeedItem.Ask("a9", 1, "10:00", "milk?", "Milk is almost empty.", false)))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Milk is almost empty."))
        compose.onNodeWithText("Milk is almost empty.").performClick()
        assertEquals(listOf("a9"), opened)
    }

    @Test fun whileListeningTheMicSendsAndShowsWhatWasHeard() {
        var mics = 0
        compose.setContent {
            PcTheme {
                HomeScreen(
                    HomeUiState(status = live, voice = HomeVoiceUi(enabled = true, listening = true, partial = "where are my", hint = "Listening\u2026 tap the mic to send")),
                    onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {}, onMic = { mics++ },
                )
            }
        }
        compose.onNodeWithText("\u201cwhere are my\u201d").assertIsDisplayed()
        compose.onNodeWithText("Listening\u2026 tap the mic to send").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop and send").performClick()
        assertEquals(1, mics)
    }

    @Test fun aMissingPermissionAsksForItInsteadOfListening() {
        var requests = 0
        var mics = 0
        compose.setContent {
            PcTheme {
                HomeScreen(
                    HomeUiState(status = live, voice = HomeVoiceUi(enabled = true, needsPermission = true, hint = "Allow the microphone to ask by voice")),
                    onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {}, onMic = { mics++ }, onRequestMic = { requests++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Ask by voice").performClick()
        assertEquals(1, requests); assertEquals(0, mics)
        compose.onNodeWithText("Allow the microphone to ask by voice").assertIsDisplayed()
    }

    @Test fun aVoiceProblemShowsItsFixedText() {
        show(HomeUiState(status = live, voice = HomeVoiceUi(enabled = true, hint = "or tap your glasses", error = "Couldn't hear that \u2014 try again")))
        compose.onNodeWithText("Couldn't hear that \u2014 try again").assertIsDisplayed()
    }
}
