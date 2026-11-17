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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.banner.BannerInputs
import com.rokid.context.phone.ui.banner.BannerPagerTags
import com.rokid.context.phone.ui.banner.BannerResolver
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.rokid.context.phone.ui.status.StatusUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val graphCtx = ApplicationProvider.getApplicationContext<Context>()
    @org.junit.Before fun freshGraph() { com.rokid.context.phone.ui.TestGraph.fresh(graphCtx) }
    @org.junit.After fun closeGraph() { com.rokid.context.phone.ui.TestGraph.close(graphCtx) }
    private val asked = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var pauses = 0
    private var banner: BannerAction? = null
    private val nudges = mutableListOf<String>()

    private val askedAgain = mutableListOf<String>()
    private fun show(state: HomeUiState) = compose.setContent {
        PcTheme { HomeScreen(state, onQuestionChange = {}, onAsk = { asked += it }, onOpenAsk = { opened += it }, onTogglePause = { pauses++ }, onBannerAction = { banner = it }, onOpenNudge = { nudges += it }, onAskAgain = { askedAgain += it }) }
    }

    private val live = StatusUi("Capturing · next 4:12", live = true, paused = false, batteryPct = 82, banner = null)

    /** M5 (forgetV2): the feed card of an answer whose source was forgotten: the question stays, the answer text is gone, "Ask again" is the user's own tap. */
    @Test fun aForgottenSourceAnswerShowsTheStateAndAskAgain() {
        show(HomeUiState(status = live, feed = listOf(FeedItem.Ask("a7", 1, "08:15", "Where are my keys?", "Removed \u2014 a source was forgotten", redacted = true, forgotten = true))))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("You asked \u201cWhere are my keys?\u201d"))
        compose.onNodeWithText("You asked \u201cWhere are my keys?\u201d").assertIsDisplayed()
        compose.onNodeWithText("Answered from a forgotten fact").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask again: Where are my keys?").performClick()
        assertEquals(listOf("a7"), askedAgain); assertEquals("nothing was asked by itself", emptyList<String>(), asked)
    }

    @Test fun anAnswerAskedAgainHasNoButton() {
        show(HomeUiState(status = live, feed = listOf(FeedItem.Ask("a8", 1, "08:15", "Where are my keys?", "x", redacted = true, forgotten = true, askedAgain = true))))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Answered from a forgotten fact", substring = true))
        compose.onNodeWithText("Answered from a forgotten fact \u2014 asked again").assertIsDisplayed()
        compose.onNodeWithTag("ask_again_a8").assertDoesNotExist()
    }

    @Test fun homeShowsPhotosTodayCount() {
        show(HomeUiState(status = live, photosToday = 3))
        compose.onNodeWithTag("photos_today").assertIsDisplayed()
        compose.onNodeWithText("3 photos today").assertIsDisplayed()
    }

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

    @Test fun homeShowsEveryBannerAsSwipeableTilesWithDots() {
        val all = BannerResolver.candidates(BannerInputs(aiBlockedText = "no key", link = LinkState.OFFLINE, pipelineEnabled = false))
        show(HomeUiState(status = StatusUi("Paused", false, true, null, all.first(), pipelineEnabled = false, banners = all)))
        compose.onNodeWithText("Fix now").assertIsDisplayed()
        compose.onNodeWithTag(BannerPagerTags.dot(0, true)).assertIsDisplayed()
        compose.onNodeWithTag(BannerPagerTags.dot(2, false)).assertIsDisplayed()
        compose.onNodeWithTag(BannerPagerTags.PAGER).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag(BannerPagerTags.dot(1, true)).assertIsDisplayed()
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
        // Home's "N photos today" line moved the hero down a little: scroll to the error before checking it.
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("AI unavailable", substring = true))
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
        assertEquals(listOf("n1"), nudges)
    }

    // Phase 7 C4

    @Test fun nudgeCardOpensDetail() {
        show(HomeUiState(status = live, feed = listOf(FeedItem.Nudge("n1", 2, "09:41", "Milk looked almost empty", "why", redacted = false))))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Milk looked almost empty"))
        compose.onNodeWithText("Milk looked almost empty").performClick()
        assertEquals(listOf("n1"), nudges)
    }

    @Test fun aRedactedNudgeSaysWhyAndDoesNotOpen() {
        show(HomeUiState(status = live, feed = listOf(FeedItem.Nudge("n1", 2, "09:41", "secret text", "why", redacted = true))))
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Removed \u2014 a source was forgotten"))
        compose.onNodeWithText("Removed \u2014 a source was forgotten").performClick()
        assertEquals(emptyList<String>(), nudges)
        compose.onAllNodesWithText("secret text").assertCountEquals(0)
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

    /** Fix 1 #3: while thinking the mic button cancels. */
    @Test fun whileThinkingTheMicCancels() {
        var mics = 0
        compose.setContent {
            PcTheme {
                HomeScreen(
                    HomeUiState(status = live, voice = HomeVoiceUi(enabled = true, thinking = true, hint = HomeVoiceUi.HINT_THINKING)),
                    onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {}, onMic = { mics++ },
                )
            }
        }
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(1, mics)
    }

    /** Fix 1 #4: a blocked mic opens the app's settings. */
    @Test fun aBlockedMicOpensAppSettings() {
        var opened = 0
        var requests = 0
        compose.setContent {
            PcTheme {
                HomeScreen(
                    HomeUiState(status = live, voice = HomeVoiceUi(enabled = true, needsPermission = true, blocked = true, hint = HomeVoiceUi.HINT_BLOCKED)),
                    onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {},
                    onRequestMic = { requests++ }, onOpenAppSettings = { opened++ },
                )
            }
        }
        compose.onNodeWithText(HomeVoiceUi.HINT_BLOCKED).assertIsDisplayed()
        compose.onNodeWithText("Open app settings").performClick()
        compose.onNodeWithContentDescription("Ask by voice").performClick()
        assertEquals(2, opened); assertEquals(0, requests)
    }

    /** Fix 1 #1: Home leaving the screen tells the caller (which cancels a listen). */
    @Test fun leavingHomeIsReported() {
        var left = 0
        val shown = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            PcTheme {
                if (shown.value) HomeScreen(
                    HomeUiState(status = live), onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {},
                    onLeave = { left++ },
                )
            }
        }
        compose.waitForIdle(); assertEquals(0, left)
        shown.value = false
        compose.waitForIdle(); assertEquals(1, left)
    }
}
