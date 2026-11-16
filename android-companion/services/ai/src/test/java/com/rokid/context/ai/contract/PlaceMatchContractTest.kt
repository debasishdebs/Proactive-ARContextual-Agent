package com.rokid.context.ai.contract

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Role
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.model.CallClass
import com.rokid.context.prompt.AssetPromptSeeds
import com.rokid.context.prompt.PromptVars
import com.rokid.context.prompt.TemplateVariables
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Place Guide B4: the PLACE_MATCH key and its strict {choice, confidence} contract (no free text, an optional box ignored). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceMatchContractTest {
    private fun decode(json: String) = RoleContracts.decode(PromptKey.PLACE_MATCH, Json.parseToJsonElement(json))

    @Test fun parsesChoiceAndConfidence() {
        assertEquals(PlaceMatchOutput("2", 0.86f), decode("""{"choice":"2","confidence":0.86}""").getOrThrow())
        assertEquals("NONE", (decode("""{"choice":"NONE","confidence":0.1}""").getOrThrow() as PlaceMatchOutput).choice)
    }

    @Test fun boxIsKeptInExtrasAndIgnored() {
        val o = decode("""{"choice":"1","confidence":0.9,"box":[0.1,0.2,0.5,0.6]}""").getOrThrow() as PlaceMatchOutput
        assertEquals("1", o.choice); assertTrue("box" in o.extras)
    }

    @Test fun missingFieldsAreAContractFailure() {
        assertTrue(decode("""{"confidence":0.9}""").isFailure)
        assertTrue(decode("""{"choice":"1"}""").isFailure)
    }

    @Test fun confidenceIsClamped() {
        assertEquals(1f, (decode("""{"choice":"1","confidence":4}""").getOrThrow() as PlaceMatchOutput).confidence, 0f)
    }

    @Test fun schemaHasNoFreeTextFieldAndNoExtras() {
        val s = Json.parseToJsonElement(DefaultSchemas.text(PromptKey.PLACE_MATCH)!!) as JsonObject
        val props = (s["properties"] as JsonObject).keys
        assertEquals(setOf("choice", "confidence", "box"), props)
        assertEquals("false", s["additionalProperties"].toString())
        assertTrue(DefaultSchemas.text(PromptKey.PLACE_MATCH)!!.contains("\"maxLength\":8"))
    }

    @Test fun keyRegistration() {
        assertEquals(Role.VISION_EXTRACT, PromptKey.PLACE_MATCH.role)
        assertTrue(PromptKey.PLACE_MATCH.requiresImage)
        assertEquals(PromptKey.INTENT_CLASSIFY.ordinal + 1, PromptKey.PLACE_MATCH.ordinal)
        assertEquals(CallClass.EXTRACT, RoleCall(PromptKey.PLACE_MATCH, PromptVars()).effectiveClass)
    }

    @Test fun seedForbidsPeopleAndCarriesTheCandidates() {
        val t = AssetPromptSeeds(ApplicationProvider.getApplicationContext()).template(PromptKey.PLACE_MATCH)
        assertTrue(t.contains("Monuments and buildings only: never identify or describe people"))
        assertTrue(t.contains("{{recent_context}}"))
        assertEquals(emptySet<String>(), TemplateVariables.missing(PromptKey.PLACE_MATCH, t))
        assertFalse(t.contains("summary"))
    }
}
