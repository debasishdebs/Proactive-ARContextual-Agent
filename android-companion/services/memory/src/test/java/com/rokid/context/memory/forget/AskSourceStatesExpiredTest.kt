package com.rokid.context.memory.forget

import com.rokid.context.memory.db.AskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/** P3: "expired" (system clean-up, answer kept whole) is a different state from "forgotten" (a user Forget erased it). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskSourceStatesExpiredTest {
    private fun ask(redacted: Boolean, states: String?) = AskEntity("q", 1, "q", "a", "[]", null, "test", redacted, sourceStates = states)

    @Test fun expiredIsItsOwnStateAndNeverAForget() {
        val json = AskSourceStates.expired(listOf("m1", "frame:f1"), listOf("nudge:n1"))
        val st = AskSourceStates.parse(json)!!
        assertEquals(AskSourceStates.SOURCE_EXPIRED, st.state); assertEquals(listOf("m1", "frame:f1"), st.cited); assertEquals(listOf("nudge:n1"), st.history)
        val a = ask(false, json)
        assertTrue(AskSourceStates.isExpired(a)); assertFalse(AskSourceStates.isForgotten(a)); assertFalse(AskSourceStates.isReasked(a))
        assertEquals("a kept answer still depends on what it cited (a user Forget of one still redacts it)", setOf("m1", "frame:f1", "nudge:n1"), AskSourceStates.dependenciesV2(a))
    }

    @Test fun forgottenStaysForgotten() {
        val a = ask(true, AskSourceStates.forgotten())
        assertTrue(AskSourceStates.isForgotten(a)); assertFalse(AskSourceStates.isExpired(a))
    }

    @Test fun theWordingNamesExpiryOnlyForExpiry() {
        assertTrue(ForgetService.REDACTED.contains("forgotten"))
        assertFalse(ForgetService.REDACTED.contains("expired"))
    }
}
