package com.rokid.context.phone.ui.onboarding

import com.rokid.context.phone.ui.MainDispatcherRule
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OnboardingFixRound1Test {
    @get:Rule val main = MainDispatcherRule()
    private fun vm(env: Env, step: Step) = OnboardingViewModel(env.deps, work = UnconfinedTestDispatcher()).also { while (it.state.value.step != step) it.next() }

    @Test fun grantDeliveredWhileNotResumedProbesOnResume() {
        val e = Env(); val v = vm(e, Step.PERMISSIONS)
        v.onMicResult(granted = true, canAskAgain = true, foreground = false) // STARTED, not RESUMED
        assertEquals(0, e.voice.rechecks)
        v.onResume(micGranted = true, notificationsGranted = false)
        assertEquals(1, e.voice.rechecks)
        v.onResume(micGranted = true, notificationsGranted = false) // the flag is cleared
        assertEquals(1, e.voice.rechecks)
    }

    @Test fun pendingProbeWaitsForThePermissionsStep() {
        val e = Env(); val v = vm(e, Step.PERMISSIONS)
        v.onMicResult(true, true, foreground = false)
        v.next()
        v.onResume(true, false)
        assertEquals(0, e.voice.rechecks)
    }

    @Test fun grantInFrontLeavesNothingPending() {
        val e = Env(); val v = vm(e, Step.PERMISSIONS)
        v.onMicResult(true, true, foreground = true)
        v.onResume(true, false)
        assertEquals(1, e.voice.rechecks)
    }

    @Test fun permissionsAreSyncedOnStepEntry() {
        val e = Env(); val v = vm(e, Step.PERMISSIONS)
        v.syncPermissions(micGranted = true, notificationsGranted = true, foreground = true)
        assertEquals(PermState.GRANTED, v.state.value.mic)
        assertEquals(PermState.GRANTED, v.state.value.notifications)
        assertEquals(1, e.voice.rechecks)
    }

    @Test fun keyIsRedactedInToString() {
        val secret = "sk-SECRET-123"
        assertFalse(AiStepState(preset = AiPreset.OPENAI, key = secret).toString().contains(secret))
        assertFalse(EndpointDraft(AiPreset.OPENAI, "https://x", null, "m", secret).toString().contains(secret))
    }

    @Test fun keyIsClearedOnContinueAndWhenLeavingTheStep() {
        val e = Env(); val v = vm(e, Step.AI)
        v.selectPreset(AiPreset.OPENAI); v.onUrl("api.example.com"); v.onKey("sk-abc"); v.testAi()
        v.primary {}
        assertEquals("", v.state.value.ai.key)
        v.back()
        assertEquals("", v.state.value.ai.key)
        val v2 = vm(Env(), Step.AI)
        v2.selectPreset(AiPreset.OPENAI); v2.onKey("sk-abc"); v2.secondary()
        v2.back()
        assertEquals("", v2.state.value.ai.key)
    }

    @Test fun seedContinueBindsTheSeed() {
        val e = Env(ai = FakeOnboardingAi(SeedEndpoint("azure", "Azure OpenAI", "gpt-4o")))
        val v = vm(e, Step.AI); v.primary {}
        assertEquals(listOf("azure"), e.ai.used)
    }

    @Test fun testIsDisabledWhileTheModelIsEmpty() {
        val v = vm(Env(), Step.AI)
        v.selectPreset(AiPreset.OPENAI)
        assertTrue(v.state.value.ai.canTest)
        v.onModel("  ")
        assertFalse(v.state.value.ai.canTest)
    }
}
