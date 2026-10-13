package com.rokid.context.memory

import com.rokid.context.memory.db.FrameDao
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryDao
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.SearchNormalizer
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.db.TraceDao
import java.time.LocalDate
import java.time.ZoneId

class FrameRepository(private val dao: FrameDao) {
    /** False when a frame with this id already exists. */
    suspend fun add(frame: FrameEntity): Boolean = dao.insertIgnore(frame) != -1L
    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun all() = dao.all()
    suspend fun byIds(ids: List<String>) = if (ids.isEmpty()) emptyList() else dao.byIds(ids)
    suspend fun deleteByIds(ids: List<String>) { if (ids.isNotEmpty()) dao.deleteByIds(ids) }
}

class MemoryRepository(private val dao: MemoryDao) {
    private fun indexed(m: MemoryEntity) = m.copy(searchText = SearchNormalizer.normalize("${m.text} ${m.tags}"))

    suspend fun add(memory: MemoryEntity) = dao.insert(indexed(memory))
    suspend fun addIgnore(memory: MemoryEntity): Boolean = dao.insertIgnore(indexed(memory)) != -1L
    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun all() = dao.all()
    suspend fun search(userText: String, limit: Int): List<MemoryEntity> =
        SearchNormalizer.toMatchQuery(userText)?.let { dao.search(it, limit) } ?: emptyList()
    suspend fun onDay(date: LocalDate, zone: ZoneId): List<MemoryEntity> {
        val r = DayBucket.bounds(date, zone)
        return dao.between(r.first, r.last + 1)
    }
    suspend fun deleteByIds(ids: List<String>) { if (ids.isNotEmpty()) dao.deleteByIds(ids) }
    /** Ids starting with [prefix], literally (LIKE wildcards escaped; startsWith re-checks LIKE's ASCII case folding). */
    suspend fun idsWithPrefix(prefix: String): List<String> = dao.idsLike(escapeLike(prefix)).filter { it.startsWith(prefix) }

    companion object {
        /** Escapes `\`, `%` and `_` with `\` for a `LIKE ... ESCAPE '\'` pattern. */
        fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

class TraceRepository(private val dao: TraceDao) {
    suspend fun upsert(trace: SummaryTraceEntity) = dao.upsert(trace)
    suspend fun addIgnore(trace: SummaryTraceEntity): Boolean = dao.insertIgnore(trace) != -1L
    suspend fun recent(limit: Int) = dao.recent(limit)
    suspend fun deleteByIds(ids: List<String>) { if (ids.isNotEmpty()) dao.deleteByIds(ids) }
}
