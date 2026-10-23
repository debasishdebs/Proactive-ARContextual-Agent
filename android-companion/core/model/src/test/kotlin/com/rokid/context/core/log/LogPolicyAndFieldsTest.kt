package com.rokid.context.core.log

import kotlin.test.Test
import kotlin.test.assertEquals

class LogPolicyAndFieldsTest {
    @Test fun routeTable() {
        assertEquals(Route(false, false), LogPolicy.route(Level.D, "T", false))
        assertEquals(Route(true, true), LogPolicy.route(Level.D, "T", true))
        assertEquals(Route(true, false), LogPolicy.route(Level.I, "T", false))
        assertEquals(Route(true, true), LogPolicy.route(Level.W, "T", false))
        assertEquals(Route(true, true), LogPolicy.route(Level.E, "T", false))
    }

    @Test fun fieldsAreCountsAndClassNamesOnly() {
        assertEquals("7", LogFields.count(7))
        assertEquals("IllegalStateException", LogFields.cls(IllegalStateException("secret")))
        assertEquals("D", LogFields.enum(Level.D))
        val anon = object : RuntimeException("x") {}
        assertEquals(true, LogFields.cls(anon).isNotEmpty())
    }
}
