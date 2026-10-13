package com.rokid.context.connectors.sources

import android.content.ContentResolver
import android.provider.ContactsContract
import com.rokid.context.memory.db.SearchNormalizer

/**
 * Contacts (R-64): only for a question. Its tokens of 3+ characters (English or Devanagari, via [SearchNormalizer])
 * are matched as word prefixes of `DISPLAY_NAME_PRIMARY`. At most 3 lines `"contact · <name>"`; never numbers or emails.
 * [names] gets the tokens (a prefilter) and returns candidate display names.
 */
class ContactsSource(private val names: suspend (List<String>) -> List<String>) : ContextSource {
    override val kind = SourceKind.CONTACTS
    override val permissions = listOf(android.Manifest.permission.READ_CONTACTS)

    override suspend fun lines(question: String?, nowMs: Long): List<String> {
        if (question.isNullOrBlank()) return emptyList()
        val tokens = SearchNormalizer.normalize(question).split(' ').filter { it.codePointCount(0, it.length) >= MIN_TOKEN }.distinct().take(MAX_TOKENS)
        if (tokens.isEmpty()) return emptyList()
        return names(tokens).asSequence()
            .filter { name -> SearchNormalizer.normalize(name).split(' ').any { w -> tokens.any { w.startsWith(it) } } }
            .map { it.replace(DIGITS, "").trim() }.filter { it.isNotEmpty() }
            .distinct().take(MAX_LINES).map { "contact \u00b7 ${it.take(MAX_NAME)}" }.toList()
    }

    companion object {
        private const val MIN_TOKEN = 3
        private const val MAX_TOKENS = 8
        private const val MAX_LINES = 3
        private const val MAX_NAME = 60
        private val DIGITS = Regex("\\p{Nd}")

        /** LIKE prefilter on DISPLAY_NAME_PRIMARY (start of the name or of a later word); the exact match runs in Kotlin. */
        fun resolver(cr: ContentResolver): suspend (List<String>) -> List<String> = { tokens ->
            val col = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
            val sel = tokens.joinToString(" OR ") { "$col LIKE ? OR $col LIKE ?" }
            val args = tokens.flatMap { listOf("$it%", "% $it%") }.toTypedArray()
            cr.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(col), sel, args, "$col ASC")?.use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext() && out.size < 200) c.getString(0)?.let(out::add)
                out
            }.orEmpty()
        }
    }
}
