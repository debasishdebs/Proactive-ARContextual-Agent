package com.rokid.context.connectors

/** `{{context_sources}}` lines (spec §5.4, R-64). Context only: never sent to the glasses. Stream C implements it. */
fun interface ContextSourceProvider {
    /** Lines for {{context_sources}}; [question] null = nudge context. Never throws; ≤ 1 500 chars total. */
    suspend fun lines(question: String?, nowMs: Long): List<String>

    companion object { val None = ContextSourceProvider { _, _ -> emptyList() } }
}
