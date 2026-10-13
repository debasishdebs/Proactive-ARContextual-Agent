package com.rokid.context.connectors.actions

import android.content.Context
import com.rokid.context.connectors.ProposalIntake

/** The action engine's process-wide graph (R-62). */
interface ActionsGraphApi {
    val intake: ProposalIntake
}

/** Placeholder (Stream 0): stores nothing. Stream B replaces the body with the ActionEngine. */
object ActionsGraph {
    private val placeholder = object : ActionsGraphApi { override val intake: ProposalIntake = ProposalIntake.None }

    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context): ActionsGraphApi = placeholder
}
