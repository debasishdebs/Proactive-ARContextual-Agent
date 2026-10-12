package com.rokid.context.glasses.hud

import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Overlay

/** Touchpad rules while a Confirm overlay shows (Live Fit `ConfirmInput`): swipe moves, tap answers, double-tap = No. */
class ConfirmInput {
    private var currentId: String? = null
    private var votedId: String? = null
    var highlightYes: Boolean = true
        private set
    val hasPending: Boolean get() = currentId != null

    /** True when a new proposal appeared; the highlight starts on ✓ (only a tap votes, spec §5.3). */
    fun onConfirm(c: Overlay.Confirm?): Boolean {
        if (c != null && c.proposalId == votedId) { currentId = null; return false } // one vote per proposal
        val isNew = c != null && c.proposalId != currentId
        currentId = c?.proposalId
        if (isNew) highlightYes = true
        return isNew
    }

    fun onSwipe() { if (currentId != null) highlightYes = !highlightYes }
    fun onTap(): Command.Confirm? = vote(highlightYes)
    fun onNo(): Command.Confirm? = vote(false)

    private fun vote(yes: Boolean): Command.Confirm? = currentId?.let { id ->
        votedId = id
        currentId = null
        Command.Confirm(id, yes)
    }
}
