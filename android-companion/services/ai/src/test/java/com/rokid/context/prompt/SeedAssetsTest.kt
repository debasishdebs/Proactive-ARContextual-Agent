package com.rokid.context.prompt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.PromptKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
}
