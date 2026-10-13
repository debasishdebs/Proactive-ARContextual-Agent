package com.rokid.context.connectors.sources

enum class SourceKind { CALENDAR, CONTACTS, NOTES, LOCATION }

/** One data source for `{{context_sources}}` (R-64). Context only: never sent to the glasses (spec §5.4). */
interface ContextSource {
    val kind: SourceKind
    val permissions: List<String>
    suspend fun lines(question: String?, nowMs: Long): List<String>
}
