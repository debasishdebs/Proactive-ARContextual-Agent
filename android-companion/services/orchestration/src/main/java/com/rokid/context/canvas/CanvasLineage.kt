package com.rokid.context.canvas

/**
 * S25 lineage in one column. A row's `batchId` is the model call that produced it; a row derived from earlier calls (a critic, a write preview) also
 * lists those, joined with [SEP], own call first. So a per-frame call (the critic) never hides the shared extract call the frame's text came from,
 * and a Forget of any frame of that shared call finds every dependent row by token (`CanvasDao`).
 */
object CanvasLineage {
    const val SEP = '|'

    /** Adds [own] to the frame's [seen] calls and returns the column value (own first, then the earlier ones), or null when there is none. */
    fun compose(seen: LinkedHashSet<String>, own: String?): String? {
        if (own != null) seen.add(own)
        if (seen.isEmpty()) return null
        return if (own == null) seen.joinToString(SEP.toString()) else (listOf(own) + (seen - own)).joinToString(SEP.toString())
    }
}
