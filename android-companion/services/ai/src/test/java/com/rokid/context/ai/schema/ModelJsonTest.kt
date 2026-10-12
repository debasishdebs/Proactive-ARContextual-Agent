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
}
