package com.rokid.context.core.model.context

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextTypesTest {
    @Test fun parsesExactNamesOnly() {
        assertEquals(IntentLabel.DRIVING, IntentLabel.parse("DRIVING"))
        assertEquals(IntentLabel.OTHER, IntentLabel.parse("driving"))
        assertEquals(IntentLabel.OTHER, IntentLabel.parse(null))
        assertEquals(IntentLabel.OTHER, IntentLabel.parse("NOPE"))
    }
}
