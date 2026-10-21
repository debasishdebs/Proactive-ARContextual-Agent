package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertEquals

class WearingStatusTest {
    @Test fun clearWordsAreVerdictsAndEverythingElseIsUnknown() {
        assertEquals(true, WearingStatus.parse("1")); assertEquals(true, WearingStatus.parse(" Worn "))
        assertEquals(false, WearingStatus.parse("0")); assertEquals(false, WearingStatus.parse("NOT_WORN")); assertEquals(false, WearingStatus.parse("take off"))
        assertEquals(null, WearingStatus.parse(null)); assertEquals(null, WearingStatus.parse("")); assertEquals(null, WearingStatus.parse("weird"))
    }

    /** The log carries one token from a fixed list, never the SDK's free text (S15 / A3-minor). */
    @Test fun logTokenAllowListOnly() {
        val allowed = setOf("worn", "not_worn", "unknown", "off", "other")
        assertEquals("unknown", WearingStatus.describe(null))
        assertEquals("worn", WearingStatus.describe("1")); assertEquals("worn", WearingStatus.describe(" Wearing_On "))
        assertEquals("not_worn", WearingStatus.describe("0")); assertEquals("not_worn", WearingStatus.describe("take off"))
        assertEquals("off", WearingStatus.describe("off")); assertEquals("unknown", WearingStatus.describe("unknown"))
        for (raw in listOf("", "a".repeat(24), "a".repeat(25), "{\"a\":1,2}", "a:b", "weird", "serial ABC123", "worn by someone")) {
            val t = WearingStatus.describe(raw)
            assertEquals(true, t in allowed, "token=$t")
            assertEquals(false, raw.length > 2 && t.contains(raw), "free text leaked: $t")
        }
        assertEquals("other", WearingStatus.describe("serial ABC123"))
    }

    @Test fun snapshotIsAppliedOnlyWhenTheWearCheckIsOn() {
        assertEquals(true, WearingStatus.snapshotToApply(true, true))
        assertEquals(false, WearingStatus.snapshotToApply(false, true))
        assertEquals(null, WearingStatus.snapshotToApply(null, true))
        assertEquals(null, WearingStatus.snapshotToApply(true, false))
        assertEquals(null, WearingStatus.snapshotToApply(false, false))
        assertEquals(null, WearingStatus.snapshotToApply(null, false))
        assertEquals(null, WearingStatus.snapshotToApply(true, null))
        assertEquals(null, WearingStatus.snapshotToApply(false, null))
        assertEquals(null, WearingStatus.snapshotToApply(null, null))
    }
}
