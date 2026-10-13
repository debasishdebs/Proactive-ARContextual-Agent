package com.rokid.context.connectors.sources

enum class SourceKind { CALENDAR, CONTACTS, NOTES, LOCATION }

/** One data source for `{{context_sources}}` (R-64). Context only: never sent to the glasses (spec §5.4). */
interface ContextSource {
    val kind: SourceKind
    val permissions: List<String>
    suspend fun lines(question: String?, nowMs: Long): List<String>
}

/** The runtime permissions each source needs; Settings › Data sources asks for exactly these when one is turned on. */
object SourcePermissions {
    fun of(k: SourceKind): List<String> = when (k) {
        SourceKind.CALENDAR -> listOf(android.Manifest.permission.READ_CALENDAR)
        SourceKind.CONTACTS -> listOf(android.Manifest.permission.READ_CONTACTS)
        SourceKind.NOTES -> emptyList()
        SourceKind.LOCATION -> listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
