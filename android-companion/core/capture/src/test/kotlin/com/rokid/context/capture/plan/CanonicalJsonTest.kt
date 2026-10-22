package com.rokid.context.capture.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

class CanonicalJsonTest {
    private fun c(s: String) = CanonicalJson.encode(Json.parseToJsonElement(s))

    @Test fun sortsKeysAndNormalizesNumbers() {
        assertEquals("""{"a":1,"b":{"x":0.5,"y":[1,2.5,0]},"c":"\u0001é"}""", c("""{ "c":"\u0001é","b":{"y":[1.0,2.50,0.0],"x":0.50},"a":1.000 }"""))
    }

    @Test fun nonFiniteRejected() {
        assertFailsWith<IllegalArgumentException> { CanonicalJson.encode(JsonPrimitive(Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { CanonicalJson.encode(JsonPrimitive(Double.POSITIVE_INFINITY)) }
    }
}
