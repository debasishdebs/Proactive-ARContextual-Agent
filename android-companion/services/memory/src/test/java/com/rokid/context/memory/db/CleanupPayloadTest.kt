package com.rokid.context.memory.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The drainer reads back exactly what the writers wrote: the parsers are the inverse of the builders (the payload text is the idempotence key). */
class CleanupPayloadTest {
    @Test fun oneRoundTripsQuotesBackslashesControlCharactersAndUnicode() {
        for (v in listOf("/data/x/a.jpg", "a\"b", "a\\b", "tab\tnl\n", "zé中", "")) {
            assertEquals("round trip of ${v.length} chars", v, CleanupPayload.parseOne(CleanupPayload.one("path", v), "path"))
        }
    }

    @Test fun oneRefusesAnotherKeyAndMalformedText() {
        assertNull(CleanupPayload.parseOne(CleanupPayload.one("path", "a"), "traceId"))
        for (bad in listOf("", "{}", "{\"path\":1}", "{\"path\":\"a\"", "{\"path\":\"a\"} x", "[]", "{\"path\":\"a\\q\"}")) assertNull(bad, CleanupPayload.parseOne(bad, "path"))
    }

    @Test fun rangeRoundTripsAndRefusesMalformedOrInvertedRanges() {
        assertEquals(5L to 9L, CleanupPayload.parseRange(CleanupPayload.range(5, 9)))
        assertEquals(0L to 0L, CleanupPayload.parseRange(CleanupPayload.range(0, 0)))
        for (bad in listOf("", "{}", "{\"fromMs\":9,\"toMs\":5}", "{\"fromMs\":\"a\",\"toMs\":5}", "{\"fromMs\":1}")) assertNull(bad, CleanupPayload.parseRange(bad))
    }
}
