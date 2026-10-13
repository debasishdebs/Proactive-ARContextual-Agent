package com.rokid.context.memory

/** Ids per `IN (:ids)` DAO call: well under API 29 SQLite's 999-variable limit (Phase 8 B4). */
internal const val MAX_IN = 500

/** De-duplicates, then calls [block] once per chunk of at most [MAX_IN] ids and concatenates the results. Empty input: no call. */
internal suspend fun <T> List<String>.inChunks(block: suspend (List<String>) -> List<T>): List<T> {
    val out = ArrayList<T>()
    for (chunk in distinct().chunked(MAX_IN)) out += block(chunk)
    return out
}

/** De-duplicates, then calls [block] once per chunk of at most [MAX_IN] ids. Empty input: no call. */
internal suspend fun List<String>.forEachChunk(block: suspend (List<String>) -> Unit) {
    for (chunk in distinct().chunked(MAX_IN)) block(chunk)
}
