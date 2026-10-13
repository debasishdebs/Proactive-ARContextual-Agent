package com.rokid.context.phone.hub

import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Actions as the hub sees it (R-62): HUD Confirm/Undo commands in; the Confirm or undo-toast overlay out. */
interface HubActions {
    /** Overlay.Confirm or an undo Overlay.Toast; null otherwise. */
    val overlay: StateFlow<Overlay?>
    fun onConfirm(proposalId: String, yes: Boolean)
    fun onUndo(proposalId: String)

    companion object {
        /** No actions (tests and the Stream 0 placeholder): no overlay, commands ignored. */
        val None: HubActions = object : HubActions {
            override val overlay: StateFlow<Overlay?> = MutableStateFlow(null)
            override fun onConfirm(proposalId: String, yes: Boolean) = Unit
            override fun onUndo(proposalId: String) = Unit
        }
    }
}

/** Nudges as the hub sees it (R-61): the Glance one-liner and the HUD nudge overlay. */
interface HubNudges {
    /** Phone clock; the hub converts it to glasses clock in the StateFrame. */
    val latest: StateFlow<NudgeLine?>
    val overlay: StateFlow<Overlay.Nudge?>

    companion object {
        val None: HubNudges = object : HubNudges {
            override val latest: StateFlow<NudgeLine?> = MutableStateFlow(null)
            override val overlay: StateFlow<Overlay.Nudge?> = MutableStateFlow(null)
        }
    }
}
