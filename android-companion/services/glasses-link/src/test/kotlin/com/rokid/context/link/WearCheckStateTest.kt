package com.rokid.context.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Codex r1 finding 3: a persisted wear check is a display/log hint; a session starts with it unknown until THIS session reads it. */
class WearCheckStateTest {
    @Test fun aStalePersistedOffNeverFeedsTheValueOfANewSession() {
        val saved = mutableListOf<Boolean>()
        val s = WearCheckState(persisted = false) { saved += it }
        assertNull(s.value.value, "restart with wear_check=0 on disk: unknown until the session reads it")
        assertEquals(false, s.persisted, "still available for the display / log")
    }

    @Test fun everyOpenReadsAgainAndAFailedReadIsUnknownNotTheLastValue() {
        val saved = mutableListOf<Boolean>()
        val s = WearCheckState(persisted = false) { saved += it }
        s.onRead(true)
        assertEquals(true, s.value.value)
        assertEquals(listOf(true), saved)
        s.onRead(null) // the next open could not read it (no background link bound)
        assertNull(s.value.value)
        s.onRead(true)
        assertEquals(true, s.value.value)
        assertEquals(listOf(true), saved, "an unchanged read is not persisted again")
    }

    @Test fun anInvalidateDropsTheValueButKeepsThePersistedHint() {
        val s = WearCheckState(persisted = null) { }
        s.onRead(false)
        s.invalidate()
        assertNull(s.value.value)
        assertEquals(false, s.persisted)
    }
}
