package com.rokid.context.ui

import com.rokid.context.ui.config.ConfigHubScreen
import com.rokid.context.ui.onboarding.OnboardingScreen
import com.rokid.context.ui.onboarding.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    @Test
    fun minimumE2eRequiresPermissionsAndProvider() {
        val missingPermissions = OnboardingState(
            permissionsReady = false,
            providerReady = true,
            optionalPermissionsSkipped = false,
        )
        val missingProvider = OnboardingState(
            permissionsReady = true,
            providerReady = false,
            optionalPermissionsSkipped = false,
        )
        val ready = OnboardingState(
            permissionsReady = true,
            providerReady = true,
            optionalPermissionsSkipped = false,
        )

        assertFalse(OnboardingScreen.canFinish(missingPermissions))
        assertFalse(OnboardingScreen.canFinish(missingProvider))
        assertTrue(OnboardingScreen.canFinish(ready))
    }

    @Test
    fun limitedModeBannerAppearsWhenOptionalPermissionsSkipped() {
        val limited = OnboardingState(
            permissionsReady = true,
            providerReady = true,
            optionalPermissionsSkipped = true,
        )
        val full = OnboardingState(
            permissionsReady = true,
            providerReady = true,
            optionalPermissionsSkipped = false,
        )

        assertTrue(OnboardingScreen.showLimitedModeBanner(limited))
        assertFalse(OnboardingScreen.showLimitedModeBanner(full))
    }

    @Test
    fun configExportExcludesSecrets() {
        val export = ConfigHubScreen.buildExportPayload(
            providerType = "cloud",
            endpoint = "https://api.example.com",
            model = "gpt-5.4",
            timeoutMs = 10_000,
            apiKey = "super-secret-key",
        )

        assertEquals("****", export.apiKeyMasked)
    }
}
