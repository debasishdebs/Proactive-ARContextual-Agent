package com.rokid.context.phone.ui.onboarding

import com.rokid.context.ai.TestResult
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.prompt.ReplyLanguage
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingAiStepTest {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(env: Env) = OnboardingViewModel(env.deps, work = UnconfinedTestDispatcher()).also { while (it.state.value.step != Step.AI) it.next() }

    @Test fun seedEndpointSelectedByDefault() {
        val env = Env(ai = FakeOnboardingAi(SeedEndpoint("azure", "Azure OpenAI", "gpt-4o")))
        val v = vm(env)
        val ai = v.state.value.ai
        assertEquals("azure", ai.seed?.id)
        assertTrue(ai.useSeed)
        assertTrue(ai.canContinue) // the seed needs no Test
    }

    @Test fun noSeedMeansNothingSelectedAndContinueDisabled() {
        val v = vm(Env())
        assertNull(v.state.value.ai.seed)
        assertFalse(v.state.value.ai.canContinue)
    }

    @Test fun testingTheSeedShowsLatency() {
        val env = Env(ai = FakeOnboardingAi(SeedEndpoint("azure", "Azure OpenAI", "gpt-4o")))
        val v = vm(env); v.testAi()
        assertEquals("Works \u00B7 420 ms", v.state.value.ai.result?.text)
        assertEquals(1, env.ai.seedTests)
    }

    @Test fun aiContinueDisabledUntilTestPasses() {
        val env = Env()
        val v = vm(env)
        v.selectPreset(AiPreset.OPENAI); v.onUrl("api.example.com/v1"); v.onKey("sk-abc123")
        env.ai.nextResult = Result.success(TestResult(false, 10, "Key rejected (401)"))
        v.testAi()
        assertFalse(v.state.value.ai.canContinue)
        assertEquals("Key rejected (401)", v.state.value.ai.result?.text)
        env.ai.nextResult = Result.success(TestResult(true, 420, "OK"))
        v.testAi()
        assertTrue(v.state.value.ai.canContinue)
        assertEquals("https://api.example.com/v1", env.ai.drafts.last().baseUrl) // normalised
    }

    @Test fun editingAfterAPassedTestDisablesContinueAgain() {
        val env = Env(); val v = vm(env)
        v.selectPreset(AiPreset.OLLAMA); v.onUrl("box.local:11434"); v.testAi()
        assertTrue(v.state.value.ai.canContinue)
        v.onUrl("other.local:11434")
        assertFalse(v.state.value.ai.canContinue)
    }

    @Test fun invalidUrlShowsCheckTheAddress() {
        val env = Env(); val v = vm(env)
        v.selectPreset(AiPreset.OPENAI); v.onUrl("not a url"); v.onKey("sk-abc123"); v.testAi()
        assertEquals("Check the address", v.state.value.ai.urlError)
        assertTrue(env.ai.drafts.isEmpty())
        assertFalse(v.state.value.ai.canContinue)
    }

    @Test fun continueWithATestedDraftBindsItsEndpoint() {
        val env = Env(); val v = vm(env)
        v.selectPreset(AiPreset.OLLAMA); v.onUrl("box.local:11434"); v.testAi()
        v.primary {}
        assertEquals(listOf("onboarding_ollama"), env.ai.used)
        assertEquals(listOf(true), env.ai.chosen) // final review I1: a tested preset is an explicit choice
        assertEquals(Step.PERSONA, v.state.value.step)
    }

    @Test fun continueWithSeedBindsTheSeedAndAiCanBeSkipped() {
        val env = Env(ai = FakeOnboardingAi(SeedEndpoint("azure", "Azure OpenAI", "gpt-4o")))
        val v = vm(env); v.primary {}
        assertEquals(listOf("azure"), env.ai.used)
        assertEquals(listOf(false), env.ai.chosen) // "Use <seed>" only fills unbound roles
        assertEquals(Step.PERSONA, v.state.value.step)
        val v2 = vm(Env()); v2.secondary()
        assertEquals(Step.PERSONA, v2.state.value.step)
    }

    @Test fun continueIsIgnoredWhileNotAllowed() {
        val v = vm(Env()); v.primary {}
        assertEquals(Step.AI, v.state.value.step)
    }

    @Test fun hindiSelectionPersists() {
        val env = Env(); val v = vm(env); v.secondary()
        v.setLanguage(ReplyLanguage.HINDI); v.setStyle(PersonaStyle.DETAILED)
        v.primary {}
        assertEquals(ReplyLanguage.HINDI, env.persona.persona.value.language)
        assertEquals(PersonaStyle.DETAILED.text, env.persona.persona.value.text)
        assertEquals(Step.GESTURES, v.state.value.step)
    }

    @Test fun untouchedPersonaWritesEnglishConcise() {
        val env = Env(); val v = vm(env); v.secondary(); v.primary {}
        assertEquals(ReplyLanguage.ENGLISH, env.persona.persona.value.language)
        assertEquals(PersonaStyle.CONCISE.text, env.persona.persona.value.text)
    }

    @Test fun doneMarksCompleteAndCallsOnDone() {
        val env = Env(); val v = vm(env); v.secondary(); v.primary {}; v.primary {}
        assertEquals(Step.DONE, v.state.value.step)
        var done = 0
        v.primary { done++ }
        assertEquals(1, env.completed)
        assertEquals(1, done)
    }

    @Test fun nextFromDoneStaysPut() {
        val v = vm(Env()); repeat(10) { v.next() }
        assertEquals(Step.DONE, v.state.value.step)
    }

    @Test fun doubleTappedDoneFinishesOnce() {
        val env = Env(); val v = vm(env); v.secondary(); v.primary {}; v.primary {}
        var done = 0
        v.primary { done++ }; v.primary { done++ }
        assertEquals(1, done)
        assertEquals(1, env.completed)
    }

    // --- BUG-R3-2: the build seed has no key -------------------------------------------------------------------

    private fun keylessSeed() = FakeOnboardingAi(SeedEndpoint("azure", "Azure OpenAI", "gpt-5.4", needsKey = true))

    @Test fun aSeedWithoutAKeyAsksForOneAndTestWaitsForIt() {
        val env = Env(ai = keylessSeed()); val v = vm(env)
        assertTrue(v.state.value.ai.seedNeedsKey)
        assertFalse(v.state.value.ai.canTest)
        v.onKey("sk-seed-123")
        assertTrue(v.state.value.ai.canTest)
        v.testAi()
        assertEquals(listOf<String?>("sk-seed-123"), env.ai.seedKeys)
        val ai = v.state.value.ai
        assertEquals(true, ai.result?.ok)
        assertEquals("", ai.key) // the typed key never outlives a passed Test
        assertFalse(ai.seedNeedsKey) // stored: the field goes
        assertTrue(ai.canContinue)
        assertFalse(ai.toString().contains("sk-seed"))
    }

    @Test fun aFailedSeedTestKeepsTheFieldAndTheTypedKey() {
        val env = Env(ai = keylessSeed()); val v = vm(env)
        env.ai.nextResult = Result.success(TestResult(false, 10, "Key rejected (401)"))
        v.onKey("sk-wrong"); v.testAi()
        assertTrue(v.state.value.ai.seedNeedsKey)
        assertEquals("sk-wrong", v.state.value.ai.key)
    }

    @Test fun aSeedThatHasAKeyTestsWithoutOne() {
        val env = Env(ai = FakeOnboardingAi(SeedEndpoint("azure", "Azure OpenAI", "gpt-4o")))
        val v = vm(env)
        assertFalse(v.state.value.ai.seedNeedsKey)
        v.testAi()
        assertEquals(listOf<String?>(null), env.ai.seedKeys)
    }

    @Test fun switchingBackToTheSeedDropsAPresetsTypedKey() {
        val env = Env(ai = keylessSeed()); val v = vm(env)
        v.selectPreset(AiPreset.OPENAI); v.onKey("sk-preset")
        v.selectSeed()
        assertEquals("", v.state.value.ai.key)
        assertFalse(v.state.value.ai.canTest)
    }

    @Test fun theAzureFormIsPrefilledFromTheBuildDefaults() {
        val env = Env(presets = { p -> if (p == AiPreset.AZURE) PresetDefaults("https://res.openai.azure.com", "2024-10-21", "gpt-5.4") else PresetDefaults.from(p, "", "") })
        val v = vm(env)
        v.selectPreset(AiPreset.AZURE)
        val ai = v.state.value.ai
        assertEquals("https://res.openai.azure.com", ai.url); assertEquals("2024-10-21", ai.apiVersion); assertEquals("gpt-5.4", ai.model)
        assertEquals("", ai.key)
        v.selectPreset(AiPreset.OPENAI)
        assertEquals("", v.state.value.ai.url); assertEquals(AiPreset.OPENAI.defaultModel, v.state.value.ai.model)
    }

    @Test fun buildDefaultsFillOnlyWhatTheBuildHas() {
        assertEquals(PresetDefaults("https://res.openai.azure.com", "2024-10-21", "dep"), PresetDefaults.from(AiPreset.AZURE, "https://res.openai.azure.com", "dep"))
        // No build endpoint: nothing to prefill (a deployment name alone doesn't make an address).
        assertEquals(PresetDefaults(model = ""), PresetDefaults.from(AiPreset.AZURE, "", "dep"))
        assertEquals(PresetDefaults(model = AiPreset.OPENAI.defaultModel), PresetDefaults.from(AiPreset.OPENAI, "https://res.openai.azure.com", "dep"))
    }
}
