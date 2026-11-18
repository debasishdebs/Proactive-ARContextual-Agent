package com.rokid.context.orchestration.proactive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v3.1 r2 P1-2: "a gate of the suggestions just closed" (a zone is approached or held, a pause was added, driving started or became unknown). Whoever shows previously delivered text (the Home feed) observes [closes]
 * and re-derives at once instead of waiting for its backstop tick. A counter, no content; the writers are the zone controller, the pause set and the driving tracker. Safe to call from any thread.
 */
object SuggestionGateEvents {
    private val v = MutableStateFlow(0L)
    val closes: StateFlow<Long> = v.asStateFlow()
    fun closed() { v.value = v.value + 1 }
}
