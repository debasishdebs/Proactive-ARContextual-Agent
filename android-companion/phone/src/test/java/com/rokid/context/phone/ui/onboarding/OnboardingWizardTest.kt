package com.rokid.context.phone.ui.onboarding

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.prompt.ReplyLanguage
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnboardingWizardTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = Env()
    // Built after the rules have set Main (field initialisers run before them).
    private val vm by lazy { OnboardingViewModel(env.deps, work = UnconfinedTestDispatcher()) }
    private var micRequests = 0
    private var done = 0

    private fun show(v: OnboardingViewModel = vm) = compose.setContent {
        PcTheme { OnboardingWizard(v.state.collectAsStateForTest(), v.callbacks(requestMic = { micRequests++ }, requestNotifications = {}, onDone = { done++ })) }
    }

    private fun goTo(step: Step) { while (vm.state.value.step != step) vm.next() }

    private fun filled(): Int = (0 until 8).count { i ->
        compose.onNodeWithTag("seg_$i").fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "filled"
    }

    @Test fun progressHasEightSegmentsAndAdvances() {
        show()
        assertEquals(1, filled())
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Continue").performClick()
        compose.waitForIdle()
        assertEquals(3, filled())
    }

    @Test fun welcomeShowsTheCopy() {
        show()
        compose.onNodeWithText("Proactive Context").assertIsDisplayed()
        compose.onNodeWithText("Your glasses notice. Your phone remembers. Ask anything about your day.").assertIsDisplayed()
    }

    @Test fun deniedForeverShowsOpenSettings() {
        vm.next()
        vm.onMicResult(granted = false, canAskAgain = false, foreground = true)
        show()
        compose.onNodeWithText("Open settings").assertIsDisplayed().performClick()
        assertEquals(1, env.appSettings)
    }

    @Test fun allowButtonRequestsTheMic() {
        vm.next(); show()
        compose.onAllNodesWithText("Allow")[0].performClick() // the mic row is first
        assertEquals(1, micRequests)
    }

    @Test fun voiceNeedsPackShowsDownloadOnApi34() {
        vm.next()
        env.voice.capabilities.value = caps(VoiceStatus.NEEDS_SPEECH_PACK, VoiceStatus.NEEDS_SPEECH_PACK, canDownload = true)
        vm.onMicResult(true, true, true)
        show()
        compose.onNodeWithText("Voice: Needs speech pack").assertIsDisplayed()
        compose.onNodeWithText("Download").assertIsDisplayed().performClick()
        assertEquals(1, env.voice.downloads)
    }

    @Test fun continueIsAlwaysEnabledOnPermissions() {
        vm.next(); show()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(Step.GLASSES, vm.state.value.step)
    }

    @Test fun glassesStepSkippable() {
        goTo(Step.GLASSES); show()
        compose.onNodeWithText("Authorize glasses").assertIsDisplayed()
        compose.onNodeWithText("Set up later").performClick()
        assertEquals(Step.WATCH, vm.state.value.step)
    }

    @Test fun watchStepShowsWaitingThenConfirmedAndSkips() {
        goTo(Step.WATCH); show()
        compose.onNodeWithTag("watch_line").assertTextContains("Waiting for the watch", substring = true)
        compose.onNodeWithText("Skip").assertIsDisplayed()
        env.watchConfirmed.value = true; compose.waitForIdle()
        compose.onNodeWithTag("watch_line").assertTextContains("Watch link confirmed", substring = true)
        compose.onNodeWithText("Continue").performClick()
        assertEquals(Step.AI, vm.state.value.step)
    }

    @Test fun connectedGlassesShowContinue() {
        env.link.value = LinkState.CONNECTED
        goTo(Step.GLASSES); show()
        compose.onNodeWithText("Connected \u2713").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(Step.WATCH, vm.state.value.step)
    }

    @Test fun aiContinueIsDisabledUntilSomethingIsUsable() {
        goTo(Step.AI); show()
        compose.onNodeWithText("Continue").assertIsNotEnabled()
        compose.onNodeWithText("Set up later").performClick()
        assertEquals(Step.PERSONA, vm.state.value.step)
    }

    @Test fun seedCardShowsAndEnablesContinue() {
        env.ai.seed = SeedEndpoint("azure", "Azure OpenAI", "gpt-4o")
        val v = OnboardingViewModel(env.deps, work = UnconfinedTestDispatcher())
        while (v.state.value.step != Step.AI) v.next()
        show(v)
        compose.onNodeWithText("Use Azure OpenAI \u2713").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsEnabled()
    }

    @Test fun personaStepOffersEnglishHindiAndStyles() {
        goTo(Step.PERSONA); show()
        compose.onNodeWithText("\u0939\u093F\u0928\u094D\u0926\u0940").performClick()
        assertEquals(ReplyLanguage.HINDI, vm.state.value.persona.language)
        compose.onNodeWithText("Friendly").performScrollTo().performClick()
        assertEquals(PersonaStyle.FRIENDLY, vm.state.value.persona.style)
    }

    @Test fun gesturesStepListsTheDefaultsReadOnly() {
        goTo(Step.GESTURES); show()
        compose.onNodeWithText("Tap \u2014 Ask").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Double-tap \u2014 Pause / resume").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Swipe \u2014 Next / previous page").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Long-press \u2014 Hi Rokid (reserved)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change these in Settings \u203A Glasses").performScrollTo().assertIsDisplayed()
    }

    @Test fun doneStepOpensHome() {
        goTo(Step.DONE); show()
        compose.onNodeWithText("You're set").assertIsDisplayed()
        compose.onNodeWithText("Open Home").performClick()
        compose.waitForIdle()
        assertEquals(1, env.completed)
        assertEquals(1, done)
    }

    @Test fun testButtonDisabledWithoutModelAndResultIsALiveRegion() {
        goTo(Step.AI)
        vm.selectPreset(AiPreset.OLLAMA); vm.onUrl("box.local:11434")
        show()
        compose.onNodeWithTag("ai_test").assertIsEnabled()
        vm.onModel("")
        compose.waitForIdle()
        compose.onNodeWithTag("ai_test").assertIsNotEnabled()
        vm.onModel("llama3.2"); vm.testAi()
        compose.waitForIdle()
        val cfg = compose.onNodeWithTag("ai_result").fetchSemanticsNode().config
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, cfg[SemanticsProperties.LiveRegion])
    }

    /** BUG-R3-2: the keyless seed shows a masked API key field, and Test enables once a key is typed. */
    @Test fun aKeylessSeedShowsAMaskedKeyField() {
        env.ai.seed = SeedEndpoint("azure", "Azure OpenAI", "gpt-5.4", needsKey = true)
        val v = OnboardingViewModel(env.deps, work = UnconfinedTestDispatcher())
        while (v.state.value.step != Step.AI) v.next()
        show(v)
        compose.onNodeWithTag("ai_seed_key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai_test").assertIsNotEnabled()
        v.onKey("sk-typed-42")
        compose.waitForIdle()
        compose.onNodeWithTag("ai_test").assertIsEnabled()
        val shown = compose.onNodeWithTag("ai_seed_key").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals(false, shown.contains("sk-typed"))
    }
}
