package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Overlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmInputTest {
    private fun c(id: String) = Overlay.Confirm(id, "Add milk?", "Shopping list")

    @Test fun aNewProposalStartsOnYes() {
        val i = ConfirmInput()
        assertTrue(i.onConfirm(c("a")))
        assertTrue(i.highlightYes)
        assertFalse(i.onConfirm(c("a")), "same id is not new")
    }

    @Test fun swipeTogglesAndTapAnswersTheHighlight() {
        val i = ConfirmInput(); i.onConfirm(c("a"))
        i.onSwipe()
        assertEquals(Command.Confirm("a", yes = false), i.onTap())
    }

    @Test fun answerFollowsAReplacedProposal() {
        val i = ConfirmInput(); i.onConfirm(c("a")); i.onSwipe()
        i.onConfirm(c("b"))
        assertEquals(Command.Confirm("b", yes = true), i.onTap())
    }

    @Test fun doubleTapMeansNo() {
        val i = ConfirmInput(); i.onConfirm(c("a"))
        assertEquals(Command.Confirm("a", yes = false), i.onNo())
    }

    @Test fun nothingPendingMeansNoAnswer() {
        val i = ConfirmInput(); i.onConfirm(c("a")); i.onConfirm(null)
        assertFalse(i.hasPending)
        assertNull(i.onTap()); assertNull(i.onNo())
    }
}
