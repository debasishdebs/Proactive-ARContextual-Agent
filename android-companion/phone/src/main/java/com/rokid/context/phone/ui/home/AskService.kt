package com.rokid.context.phone.ui.home

import com.rokid.context.orchestration.AskOutcome

/** Seam for the Home ask path: Phase 4 → `AskActions.ask`; Phase 5 → R-52 answer path; Phase 6 adds voice. */
fun interface AskService {
    suspend fun ask(question: String): AskOutcome
}
