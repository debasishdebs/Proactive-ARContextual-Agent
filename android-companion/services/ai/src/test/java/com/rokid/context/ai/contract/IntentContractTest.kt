package com.rokid.context.ai.contract

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Role
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.core.model.context.IntentLabel
import com.rokid.context.model.CallClass
import com.rokid.context.prompt.AssetPromptSeeds
import com.rokid.context.prompt.PromptVars
import com.rokid.context.prompt.TemplateVariables
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave B §7.1: the INTENT_CLASSIFY prompt key and its {label, confidence} output contract. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IntentContractTest {
    private fun decode(json: String) = RoleContracts.decode(PromptKey.INTENT_CLASSIFY, Json.parseToJsonElement(json))

    @Test fun parsesLabelAndConfidence() {
        assertEquals(IntentOutput(IntentLabel.DRIVING, 0.8f), decode("""{"label":"DRIVING","confidence":0.8}""").getOrThrow())
    }

    @Test fun unknownLabelIsOther() {
        assertEquals(IntentLabel.OTHER, (decode("""{"label":"FLYING","confidence":0.5}""").getOrThrow() as IntentOutput).label)
    }

    @Test fun confidenceIsClamped() {
        assertEquals(1.0f, (decode("""{"label":"WALKING","confidence":1.7}""").getOrThrow() as IntentOutput).confidence, 0f)
        assertEquals(0.0f, (decode("""{"label":"WALKING","confidence":-3}""").getOrThrow() as IntentOutput).confidence, 0f)
    }

    @Test fun missingLabelIsAContractFailure() {
        assertTrue(decode("""{"confidence":0.9}""").isFailure)
        assertTrue(decode("""{"label":"DRIVING"}""").isFailure)
    }

    @Test fun keyRegistrationAndOrder() {
        assertEquals(PromptKey.VISION_EXTRACT, PromptKey.of(Role.VISION_EXTRACT))
        assertEquals(Role.VISION_EXTRACT, PromptKey.INTENT_CLASSIFY.role)
        assertEquals("Activity from a photo", PromptKey.INTENT_CLASSIFY.label)
        assertEquals(PromptKey.CLUSTER_SUMMARY.ordinal + 1, PromptKey.INTENT_CLASSIFY.ordinal)
        assertEquals(PromptKey.INTENT_CLASSIFY, PromptKey.parse("INTENT_CLASSIFY"))
    }

    @Test fun defaultCallClassIsExtract() {
        assertEquals(CallClass.EXTRACT, RoleCall(PromptKey.INTENT_CLASSIFY, PromptVars()).effectiveClass)
    }

    @Test fun schemaIsTheSpecOne() {
        val s = Json.parseToJsonElement(DefaultSchemas.text(PromptKey.INTENT_CLASSIFY)!!) as JsonObject
        assertEquals(Json.parseToJsonElement("""{"type":"object","properties":{"label":{"type":"string","enum":["SITTING_IDLE","WORKOUT","COMPUTER_WORK","DRIVING","VEHICLE_PASSENGER","FLIGHT_CABIN","WALKING","OTHER"]},"confidence":{"type":"number"}},"required":["label","confidence"]}"""), s)
    }

    @Test fun seedIsTheSpecTextAndNeedsNoVariables() {
        val t = AssetPromptSeeds(ApplicationProvider.getApplicationContext()).template(PromptKey.INTENT_CLASSIFY).trim()
        assertTrue(t.startsWith("You see one photo from smart glasses, from the wearer's point of view. Classify what the wearer is doing. Labels: SITTING_IDLE (sitting or standing still, not using a screen)"))
        assertTrue(t.endsWith("""Reply only with JSON: {"label": "<one label>", "confidence": <0 to 1>}."""))
        assertEquals(emptySet<String>(), TemplateVariables.missing(PromptKey.INTENT_CLASSIFY, t))
        assertNotNull(t)
    }
}
