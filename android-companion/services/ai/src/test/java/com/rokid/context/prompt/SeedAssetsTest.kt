package com.rokid.context.prompt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.PromptKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SeedAssetsTest {
    @Test fun everySeedUsesOnlyKnownVariablesAndHasTheRequiredOnes() {
        val seeds = AssetPromptSeeds(ApplicationProvider.getApplicationContext())
        PromptKey.entries.forEach { k ->
            val t = seeds.template(k)
            assertNotEquals("$k fell back to the built-in seed", BuiltInPromptSeeds.template(k), t)
            assertEquals("$k unknown", emptyList<String>(), TemplateVariables.unknown(t))
            assertEquals("$k missing", emptySet<String>(), TemplateVariables.missing(k, t))
        }
        PromptKey.entries.forEach { k ->
            assertEquals("$k built-in", emptySet<String>(), TemplateVariables.missing(k, BuiltInPromptSeeds.template(k)))
        }
    }

    @Test fun missingAssetFallsBackAndLogsOnlyTheClassAndKey() {
        ShadowLog.clear()
        val seeds = AssetPromptSeeds(ApplicationProvider.getApplicationContext(), dir = "prompts/absent")
        assertEquals(BuiltInPromptSeeds.template(PromptKey.NUDGE), seeds.template(PromptKey.NUDGE))
        val logs = ShadowLog.getLogsForTag("AssetPromptSeeds")
        assertEquals(1, logs.size)
        assertEquals("Seed asset missing for NUDGE; using the built-in seed", logs[0].msg)
        assertEquals(null, logs[0].throwable)
    }

    /**
     * The shipped ANSWER default (asset seed + default schema) is pinned. When it changes, add this fingerprint to
     * PromptDefaults.PREVIOUS so installs still on it are upgraded, then update it here.
     */
    @Test fun shippedAnswerDefaultIsPinnedAndThePreviousOneUpgrades() {
        val seeds = AssetPromptSeeds(ApplicationProvider.getApplicationContext())
        val current = PromptDefaults.fingerprint(seeds.template(PromptKey.ANSWER).trim(), com.rokid.context.ai.contract.DefaultSchemas.text(PromptKey.ANSWER))
        assertEquals("ee96b07885414eccb3067b4f1623df27a5a6d00725a6bea33797d2c5dc8c1dc1", current)
        assertEquals(true, PromptDefaults.PREVIOUS.getValue(PromptKey.ANSWER).contains("4235a1301a8aad35e73715eef4331716edc854d90159cf64d2a7f0365b40e7a4"))
        assertEquals(false, PromptDefaults.PREVIOUS.getValue(PromptKey.ANSWER).contains(current))
    }
}
