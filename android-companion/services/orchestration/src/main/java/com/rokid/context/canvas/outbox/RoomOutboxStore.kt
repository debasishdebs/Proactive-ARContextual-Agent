package com.rokid.context.canvas.outbox

import com.rokid.context.memory.db.ContextDatabase

/**
 * The Room `cleanup_outbox` for the drainer. Rows are DELETED on confirm (not acknowledged): an acknowledged row would keep its unique (target, payload) key and silently swallow a later
 * re-queue of the same path. The attempt book is one `canvas_meta` value of `id:count:lastAtMs` entries (ids and numbers only; no schema change).
 */
class RoomOutboxStore(private val db: ContextDatabase) : OutboxStore {
    override suspend fun due(limit: Int, excludeIds: Collection<Long>): List<OutboxRow> =
        db.momentDao().dueOutbox(limit, excludeIds.take(EXCLUDE_MAX)).map { OutboxRow(it.id, it.target, it.payloadJson) }
    override suspend fun count(): Int = db.momentDao().pendingOutboxCount()
    override suspend fun existing(ids: Collection<Long>): Set<Long> = ids.toList().chunked(500).flatMap { db.momentDao().outboxIdsAmong(it) }.toSet()
    override suspend fun trim(target: String, keep: Int): Int = db.momentDao().trimOutbox(target, keep)
    override suspend fun remove(id: Long) { db.momentDao().removeOutbox(id) }

    override suspend fun attempts(): Map<Long, OutboxAttempt> = (db.canvasSlice3Dao().meta(KEY) ?: "").split(';').mapNotNull { e ->
        val p = e.split(':'); val id = p.getOrNull(0)?.toLongOrNull(); val n = p.getOrNull(1)?.toIntOrNull(); val at = p.getOrNull(2)?.toLongOrNull()
        if (p.size == 3 && id != null && n != null && at != null && n > 0) id to OutboxAttempt(n, at) else null
    }.toMap()

    override suspend fun saveAttempts(m: Map<Long, OutboxAttempt>) {
        db.canvasSlice3Dao().putMeta(KEY, m.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.count}:${it.value.lastAtMs}" })
    }

    companion object { const val KEY = "outbox_attempts"; const val EXCLUDE_MAX = 500 }
}
