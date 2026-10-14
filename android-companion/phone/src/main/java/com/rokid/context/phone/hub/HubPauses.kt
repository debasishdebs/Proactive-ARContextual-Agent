package com.rokid.context.phone.hub

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.QuietWindow
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.PauseHistory
import com.rokid.context.memory.db.PauseDao
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.memory.db.PauseRecordEntity
import java.time.ZoneId
import java.util.UUID

/**
 * Phone pause set (spec §5.5), persisted in Room: `pause_record` = active records, `pause_interval` = retained history.
 * Instants are phone clock. A record ends only by its own expiry or a Resume naming it; nothing resumes implicitly.
 */
class HubPauses(
    private val dao: PauseDao,
    /** Quiet hours from the HUD settings; null = off. */
    private val quietHours: () -> QuietHours? = { null },
    /** The zone quiet hours are evaluated in. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** Last, so `HubPauses(dao) { t }` keeps passing the clock. */
    private val now: () -> Long = System::currentTimeMillis,
) : PauseHistory {
    /**
     * Active records; expired ones are first closed at their `untilMs` and removed (one transaction). Inside a quiet-hours
     * occurrence that was never created (open or closed interval at its start), a fresh QUIET_HOURS record is added, so
     * each occurrence gets its own id and a resumed occurrence never comes back.
     */
    suspend fun active(): List<PauseRecord> {
        val t = now()
        val (live, expired) = dao.records().map { it.toModel() }.partition { it.isActiveAt(t) }
        if (expired.isNotEmpty()) dao.expire(expired.map { it.pauseId }, expired.associate { it.pauseId to (it.untilMs ?: t) })
        return live + listOfNotNull(quietRecord(t, live))
    }

    private suspend fun quietRecord(t: Long, live: List<PauseRecord>): PauseRecord? {
        val q = quietHours() ?: return null
        val occ = QuietWindow.occurrenceAt(q, t, zone()) ?: return null
        // A live QUIET_HOURS record (e.g. minted before a zone change) already covers this window; it keeps its end.
        if (live.any { it.origin == PauseOrigin.QUIET_HOURS }) return null
        if (dao.quietOccurrence(occ.first, occ.last + 1) != null) return null
        val record = PauseRecord(UUID.randomUUID().toString(), PauseOrigin.QUIET_HOURS, occ.first, occ.last + 1)
        add(record)
        return record
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

    /**
     * Quiet hours were turned off (BUG-R2-6): ends every stored QUIET_HOURS record now (the phone's and any union'd from
     * the glasses), by name like any Resume, so the glasses get the ids in `resumedPauseIds`. Returns the ids ended.
     */
    suspend fun endQuietHours(): Set<String> {
        val ids = stored().filter { it.origin == PauseOrigin.QUIET_HOURS }.map { it.pauseId }.toSet()
        resume(ids)
        return ids
    }

    /** Ids the phone resumed (closed intervals <= 7 d, no live record, newest 200), sent as `HudSettings.resumedPauseIds`. */
    suspend fun recentlyResumedIds(): List<String> {
        active() // expire first, so the list reflects now
        return dao.resumedSince(now() - FrameAdmission.HISTORY_MS)
    }

    override suspend fun pausedAt(phoneMs: Long): Boolean {
        active() // close anything that expired before checking
        return dao.countCovering(phoneMs) > 0
    }

    /**
     * Closed intervals are kept 7 days as history (spec §5.5); frames older than that are dropped by [FrameAdmission].
     * Past that they become id-only tombstones (zero length, so they cover nothing) for [TOMBSTONE_MS], so a late echo
     * of a resumed id is never re-inserted by [add] (fix round 1, I2).
     */
    suspend fun pruneHistory() {
        active() // expire first, so only true orphans remain open without a record
        dao.closeOrphanIntervals(now())
        dao.collapseEndedBefore(now() - FrameAdmission.HISTORY_MS)
        dao.pruneEndedBefore(now() - TOMBSTONE_MS)
    }

    /** The stored active records, without expiring or minting anything (a read for a Resume before the hub loads). */
    suspend fun stored(): List<PauseRecord> = now().let { t -> dao.records().map { it.toModel() }.filter { it.isActiveAt(t) } }

    /**
     * Read-only form of "is [active] non-empty" (nothing expired or minted): a stored record active now, or now inside a
     * quiet-hours occurrence that was never created. A resumed occurrence (closed interval) does not pause (final review I2).
     */
    suspend fun pausedNow(): Boolean {
        if (stored().isNotEmpty()) return true
        val q = quietHours() ?: return false
        val occ = QuietWindow.occurrenceAt(q, now(), zone()) ?: return false
        return dao.quietOccurrence(occ.first, occ.last + 1) == null
    }

    companion object {
        const val TOMBSTONE_MS = 90 * 24 * 3_600_000L
    }
}

private fun PauseRecordEntity.toModel() =
    PauseRecord(pauseId, runCatching { PauseOrigin.valueOf(origin) }.getOrDefault(PauseOrigin.PHONE), createdAtMs, untilMs)

private fun PauseRecord.toEntity() = PauseRecordEntity(pauseId, origin.name, createdAtMs, untilMs)
