package com.rokid.context.ai.schema

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelJsonTest {
    private val want = Json.parseToJsonElement("""{"answer":"hi","sources":[]}""")

    @Test fun plainJson() = assertEquals(want, ModelJson.parse(""" {"answer":"hi","sources":[]} """))

    @Test fun fencedJsonWithProseIsParsed() {
        assertEquals(want, ModelJson.parse("Sure!\n```json\n{\"answer\":\"hi\",\"sources\":[]}\n```\nAnything else?"))
        assertEquals(want, ModelJson.parse("Here you go: {\"answer\":\"hi\",\"sources\":[]} Hope that helps."))
    }

    @Test fun nothingParseable() {
        assertNull(ModelJson.parse("I don't know."))
        assertNull(ModelJson.parse("{\"answer\": "))
    }

    @Test fun earliestOpenerWinsAndBracketMatchingIsStringAware() {
        assertEquals(Json.parseToJsonElement("[{\"a\":1}]"), ModelJson.parse("Result: [{\"a\":1}]"))
        assertEquals(Json.parseToJsonElement("{\"a\":1}"), ModelJson.parse("{\"a\":1} (see {x})"))
        assertEquals(Json.parseToJsonElement("{\"a\":\"}{\"}"), ModelJson.parse("ok {\"a\":\"}{\"} done"))
    }

    @Test fun everyFencedBlockIsTriedInOrder() =
        assertEquals(want, ModelJson.parse("```text\nnot json\n```\n```json\n{\"answer\":\"hi\",\"sources\":[]}\n```"))

    @Test fun inputNestedDeeperThanSixtyFourIsRejected() {
        assertNull(ModelJson.parse("[".repeat(100_000) + "]".repeat(100_000)))
        assertNull(ModelJson.parse("x " + "{\"a\":".repeat(70) + "1" + "}".repeat(70)))
        assertEquals(Json.parseToJsonElement("[[1]]"), ModelJson.parse("[[1]]"))
    }
}
