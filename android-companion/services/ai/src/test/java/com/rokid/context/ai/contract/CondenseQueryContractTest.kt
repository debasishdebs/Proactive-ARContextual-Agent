package com.rokid.context.ai.contract

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Role
import com.rokid.context.ai.run.RoleCall
import com.rokid.context.model.CallClass
import com.rokid.context.prompt.AssetPromptSeeds
import com.rokid.context.prompt.PromptVars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Retrieval quality T10: the CONDENSE_QUERY key and its strict {query, lang} contract (short strings, nothing else). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CondenseQueryContractTest {
    private fun decode(json: String) = RoleContracts.decode(PromptKey.CONDENSE_QUERY, Json.parseToJsonElement(json))

    @Test fun parsesQueryAndLang() {
        assertEquals(CondenseOutput("bitter gourd market", "hi"), decode("""{"query":"bitter gourd market","lang":"HI"}""").getOrThrow())
        assertEquals(CondenseOutput("football", ""), decode("""{"query":"football"}""").getOrThrow())
    }

    @Test fun aMissingQueryIsAContractFailure() {
        assertTrue(decode("""{"lang":"en"}""").isFailure)
        assertTrue(decode("""[1]""").isFailure)
    }

    @Test fun fieldsAreBounded() {
        val o = decode("""{"query":"${"a".repeat(300)}","lang":"${"x".repeat(40)}"}""").getOrThrow() as CondenseOutput
        assertEquals(120, o.query.length); assertEquals(12, o.lang.length)
    }

    @Test fun schemaIsStrictAndShort() {
        val s = Json.parseToJsonElement(DefaultSchemas.text(PromptKey.CONDENSE_QUERY)!!) as JsonObject
        assertEquals(setOf("query", "lang"), (s["properties"] as JsonObject).keys)
        assertEquals("false", s["additionalProperties"].toString())
    }

    @Test fun keyRegistrationAndNoPhotos() {
        assertEquals(Role.ANSWER, PromptKey.CONDENSE_QUERY.role)
        assertFalse(PromptKey.CONDENSE_QUERY.requiresImage)
        assertEquals(ImageUse.NONE, PromptKey.CONDENSE_QUERY.imageUse)
        assertEquals(PromptKey.TIME_ANCHOR.ordinal + 1, PromptKey.CONDENSE_QUERY.ordinal)
        assertEquals(CallClass.USER, RoleCall(PromptKey.CONDENSE_QUERY, PromptVars(), callClass = CallClass.USER).effectiveClass)
    }

    @Test fun theSeedAsksForEnglishKeywordsAndNoDateOrQuestionWords() {
        val seed = AssetPromptSeeds(ApplicationProvider.getApplicationContext()).template(PromptKey.CONDENSE_QUERY)
        assertTrue(seed.contains("{{recent_context}}"))
        for (w in listOf("English", "Date and time words", "question words", "pronouns", "karela", "\"lang\"")) assertTrue(w, seed.contains(w, ignoreCase = true))
        assertFalse(seed.contains("{{retrieved_memories}}"))
    }
}
