package com.rokid.context.phone.ui.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgetCopyTest {
    /** removeMark forgets a photo that arrived late (the undo fence and the gated photo Forget), so the dialog must not promise "no photo". */
    @Test fun markBodyIsTruthfulAboutALatePhoto() {
        assertEquals("The mark is deleted, and the Moment made from it goes with it. Any photo taken for this mark is forgotten too.", ForgetCopy.MARK_BODY)
        assertFalse(ForgetCopy.MARK_BODY.contains("It has no photo"))
        assertTrue(ForgetCopy.MARK_BODY.contains("forgotten too"))
    }
}
