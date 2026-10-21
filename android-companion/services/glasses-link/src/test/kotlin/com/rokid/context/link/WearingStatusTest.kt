package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertEquals

class WearingStatusTest {
    @Test fun clearWordsAreVerdictsAndEverythingElseIsUnknown() {
        assertEquals(true, WearingStatus.parse("1")); assertEquals(true, WearingStatus.parse(" Worn "))
        assertEquals(false, WearingStatus.parse("0")); assertEquals(false, WearingStatus.parse("NOT_WORN")); assertEquals(false, WearingStatus.parse("take off"))
        assertEquals(null, WearingStatus.parse(null)); assertEquals(null, WearingStatus.parse("")); assertEquals(null, WearingStatus.parse("weird"))
    }

    @Test fun describeShowsShortSafeTokensAndOnlyTheLengthOfAnythingElse() {
        assertEquals("<null>", WearingStatus.describe(null))
        assertEquals("1", WearingStatus.describe("1"))
        assertEquals("wearing_on", WearingStatus.describe("wearing_on"))
        assertEquals("a".repeat(24), WearingStatus.describe("a".repeat(24)))
        assertEquals("<other len=25>", WearingStatus.describe("a".repeat(25)))
        assertEquals("<other len=9>", WearingStatus.describe("{\"a\":1,2}"))
        assertEquals("<other len=3>", WearingStatus.describe("a:b"))
        assertEquals("<other len=0>", WearingStatus.describe(""))
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
