package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertEquals

class WearingStatusTest {
    @Test fun clearWordsAreVerdictsAndEverythingElseIsUnknown() {
        assertEquals(true, WearingStatus.parse("1")); assertEquals(true, WearingStatus.parse(" Worn "))
        assertEquals(false, WearingStatus.parse("0")); assertEquals(false, WearingStatus.parse("NOT_WORN")); assertEquals(false, WearingStatus.parse("take off"))
        assertEquals(null, WearingStatus.parse(null)); assertEquals(null, WearingStatus.parse("")); assertEquals(null, WearingStatus.parse("weird"))
    }
}
