package com.rokid.context.memory

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.moment.DerivedField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** One Scene as the Activity list shows it: the row, its words (the fresh summary, else the fresh caption, else null) and how many Moments it holds. */
data class SceneCard(val scene: SceneEntity, val text: String?, val parts: Int)

/** One member Moment of a Scene: its second, and the ids of what it holds (a memory, a photo, a mark), by reference only. */
data class SceneMember(val momentId: String, val atMs: Long, val memoryIds: List<String>, val frameIds: List<String>, val markIds: List<String>)

/**
 * Read-only access to Scenes for the Activity page (no write, no schema change). A derived text (summary, caption) is returned only while it is fresh AND was built from the Scene's current input
 * revision, exactly the rule of `SceneInputExecutor`, so a Scene that lost a Moment to a Forget shows no old text. Every multi-table read runs in one transaction (a Forget cannot commit in between).
 */
class SceneRepository(private val db: ContextDatabase) {
    /** One keyset page of the Scenes starting in [range], newest first, strictly after [after], with their words and member counts. */
    suspend fun pageCards(range: LongRange, after: Cursor, n: Int): List<SceneCard> = db.withTransaction {
        cardsOf(db.sceneDao().pageIn(range.first, range.last + 1, after.key, after.id, n))
    }

    /** The named Scenes, newest first; a Scene that is gone is simply absent. */
    suspend fun cards(ids: List<String>): List<SceneCard> = db.withTransaction {
        val rows = ids.distinct().chunked(MAX_IN).flatMap { db.sceneDao().scenesByIds(it) }.sortedWith(compareByDescending<SceneEntity> { it.startMs }.thenByDescending { it.sceneId })
        cardsOf(rows)
    }

    /** The start time of every Scene starting in [range], live. */
    fun startsIn(range: LongRange): Flow<List<Long>> = db.sceneDao().observeStarts(range.first, range.last + 1).distinctUntilChanged()
    suspend fun startTimes(range: LongRange): List<Long> = db.sceneDao().startsIn(range.first, range.last + 1)

    /** The Moments of [sceneId], oldest first, each with what it holds (references only). */
    suspend fun members(sceneId: String): List<SceneMember> = db.withTransaction {
        val parts = db.sceneDao().parts(sceneId).filter { it.kind == ScenePartKind.MOMENT }
        if (parts.isEmpty()) return@withTransaction emptyList()
        val held = parts.map { it.refId }.chunked(MAX_IN).flatMap { db.momentDao().partsOf(it) }.groupBy { it.momentId }
        parts.sortedWith(compareBy({ it.partTimeMs }, { it.refId })).map { p ->
            val mine = held[p.refId].orEmpty()
            SceneMember(
                p.refId, p.partTimeMs,
                mine.filter { it.kind == MomentPartKind.MEMORY }.map { it.refId }, mine.filter { it.kind == MomentPartKind.FRAME }.map { it.refId }, mine.filter { it.kind == MomentPartKind.MARK }.map { it.refId },
            )
        }
    }

    private suspend fun cardsOf(rows: List<SceneEntity>): List<SceneCard> {
        if (rows.isEmpty()) return emptyList()
        val dao = db.sceneDao()
        val ids = rows.map { it.sceneId }
        val counts = ids.chunked(MAX_IN).flatMap { dao.momentCounts(it) }.associate { it.sceneId to it.parts }
        val seqs = ids.chunked(MAX_IN).flatMap { dao.inputSeqs(it) }.associate { it.sceneId to it.seq }
        val text = ids.chunked(MAX_IN).flatMap { dao.freshText(it) }.filter { it.builtRev == (seqs[it.objectId] ?: 0) && !it.value.isNullOrBlank() }.groupBy { it.objectId }
        return rows.map { s ->
            val own = text[s.sceneId].orEmpty()
            val words = (own.firstOrNull { it.field == DerivedField.SUMMARY } ?: own.firstOrNull { it.field == DerivedField.CAPTION })?.value?.trim()
            SceneCard(s, words, counts[s.sceneId] ?: 0)
        }
    }

    private companion object { const val MAX_IN = 900 }
}
