package com.rokid.context.canvas.dump

import kotlinx.serialization.json.JsonObject

/** One item of a dump page: its paging [cursor] (the value a client sends back as `after`) and its JSON. */
class DumpItem(val cursor: String, val json: JsonObject)

/**
 * The read side of the Developer dump API (design 2026-11-26): everything the route may answer, as JSON items, READ-ONLY. The production implementation is [DumpReader]; the route tests use a fake.
 * Items a Forget removed (tombstoned Moments and photos) never come back. Every reader call may throw; the route answers a fixed 500.
 */
interface DumpSource {
    suspend fun manifest(): JsonObject
    /** With [ascending] false every page reads newest first and [after] is the last item of the previous page (the page holds the items BEFORE it). */
    /** Moments whose second lies in [fromMs, toMs], after the Moment id [after] (exclusive), at most [limit]. */
    suspend fun moments(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean = true): List<DumpItem>
    /** Scenes starting in [fromMs, toMs], after the cursor [after] (`<startMs>:<sceneId>`). */
    suspend fun scenes(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean = true): List<DumpItem>
    /** Marks (not their intent rows) in [fromMs, toMs], after the cursor [after] (`<atMs>:<labelId>`). */
    suspend fun marks(fromMs: Long, toMs: Long, after: String?, limit: Int, ascending: Boolean = true): List<DumpItem>
    /** The user's pins (a small list: no paging). */
    suspend fun pins(): List<JsonObject>
    /** The stored JPEG (or PNG, WebP) bytes of a live photo and its content type, null when the photo is gone, forgotten, unreadable or over [MAX_PHOTO_BYTES]. */
    suspend fun photo(frameId: String): DumpPhoto?

    companion object {
        const val MAX_PHOTO_BYTES = 12 * 1024 * 1024
        const val MAX_PAGE_ITEMS = 200
        const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    }
}

class DumpPhoto(val bytes: ByteArray, val contentType: String)
