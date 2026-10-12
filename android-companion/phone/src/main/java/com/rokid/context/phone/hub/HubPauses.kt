package com.rokid.context.phone.hub

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.PauseHistory
import com.rokid.context.memory.db.PauseDao
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.memory.db.PauseRecordEntity

/**
 * Phone pause set (spec §5.5), persisted in Room: `pause_record` = active records, `pause_interval` = retained history.
 * Instants are phone clock. A record ends only by its own expiry or a Resume naming it; nothing resumes implicitly.
 */
class HubPauses(private val dao: PauseDao, private val now: () -> Long = System::currentTimeMillis) : PauseHistory {
    /** Active records; expired ones are first closed at their `untilMs` and removed. */
    suspend fun active(): List<PauseRecord> {
        val t = now()
        val (live, expired) = dao.records().map { it.toModel() }.partition { it.isActiveAt(t) }
        expired.forEach { dao.closeInterval(it.pauseId, it.untilMs ?: t) }
        if (expired.isNotEmpty()) dao.deleteRecords(expired.map { it.pauseId })
        return live
    }

    /** Union: most restrictive wins (null end beats any end, else the later end); a resumed id is never resurrected. */
    suspend fun add(record: PauseRecord) = dao.addMerged(record.toEntity(), record.isActiveAt(now()), now())

    suspend fun union(records: List<PauseRecord>) = records.forEach { add(it) }

    /**
     * [exact] is the offset-converted form of a record first stored with `ClockOffset.anchoredAt` (no offset yet).
     * Only widens: the start moves earlier (also on a resumed pause's interval) and the end merges like [add].
     */
    suspend fun reanchor(exact: PauseRecord) {
        dao.widenStart(exact.pauseId, exact.createdAtMs)
        add(exact)
    }

    /**
     * Ends only the named records (spec §5.5: a Resume never clears pauses its sender did not know), at [atMs] (phone
     * clock; never later than now). A past [atMs] is a Resume made offline: frames captured after it are admitted.
     */
    suspend fun resume(clears: Set<String>, atMs: Long? = null) {
        if (clears.isEmpty()) return
        val t = now()
        dao.resumeNamed(clears, atMs?.coerceAtMost(t) ?: t)
    }

    override suspend fun pausedAt(phoneMs: Long): Boolean {
        active() // close anything that expired before checking
        return dao.countCovering(phoneMs) > 0
    }

    /** Closed intervals are kept 7 days (spec §5.5); frames older than that are dropped by [FrameAdmission]. */
    suspend fun pruneHistory() {
        active() // expire first, so only true orphans remain open without a record
        dao.closeOrphanIntervals(now())
        dao.pruneEndedBefore(now() - FrameAdmission.HISTORY_MS)
    }
}

private fun PauseRecordEntity.toModel() =
    PauseRecord(pauseId, runCatching { PauseOrigin.valueOf(origin) }.getOrDefault(PauseOrigin.PHONE), createdAtMs, untilMs)

private fun PauseRecord.toEntity() = PauseRecordEntity(pauseId, origin.name, createdAtMs, untilMs)
