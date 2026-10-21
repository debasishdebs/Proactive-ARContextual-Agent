package com.rokid.context.core.model.context

import kotlin.test.Test
import kotlin.test.assertEquals

class ContextTypesTest {
    @Test fun parsesNamesIgnoringCaseAndSpaces() {
        assertEquals(IntentLabel.DRIVING, IntentLabel.parse("DRIVING"))
        assertEquals(IntentLabel.DRIVING, IntentLabel.parse("driving"))
        assertEquals(IntentLabel.VEHICLE_PASSENGER, IntentLabel.parse(" Vehicle_Passenger "))
        assertEquals(IntentLabel.OTHER, IntentLabel.parse(null))
        assertEquals(IntentLabel.OTHER, IntentLabel.parse("NOPE"))
    }
}
