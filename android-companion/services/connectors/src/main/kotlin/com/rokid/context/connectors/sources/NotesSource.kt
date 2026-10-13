package com.rokid.context.connectors.sources

import java.math.BigDecimal

data class ShoppingLineRow(val name: String, val qty: Double?, val unit: String?)

/** Notes (R-64): the open shopping list (≤ 10) as `"shopping · <name>[ ×<qty><unit>]"`, then notes (≤ 5) as `"note · <text ≤ 200>"`. */
class NotesSource(private val shopping: suspend () -> List<ShoppingLineRow>, private val notes: suspend () -> List<String>) : ContextSource {
    override val kind = SourceKind.NOTES
    override val permissions = emptyList<String>()

    override suspend fun lines(question: String?, nowMs: Long): List<String> =
        shopping().map { s -> "shopping \u00b7 ${s.name}" + (s.qty?.let { q -> " \u00d7${qty(q)}${s.unit.orEmpty()}" } ?: "") } +
            notes().map { "note \u00b7 ${it.take(MAX_NOTE)}" }

    companion object {
        private const val MAX_NOTE = 200

        private fun qty(q: Double): String = BigDecimal.valueOf(q).stripTrailingZeros().toPlainString()
    }
}
