package com.rokid.context.phone.hub

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.QuietWindow
import com.rokid.context.core.model.zone.ZonePause
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.PauseHistory
import com.rokid.context.memory.db.PauseDao
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.memory.db.PauseRecordEntity
import com.rokid.context.phone.zone.ClockGuard
import com.rokid.context.phone.zone.ZoneHold
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
    suspend fun add(record: PauseRecord) { dao.addMerged(record.toEntity(), record.isActiveAt(now()), now()); com.rokid.context.orchestration.proactive.SuggestionGateEvents.closed() } // v3.1 r2: the Home feed re-derives at once (a pause is no store change)

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
        // Pause zones: no Resume (the app's, the glasses', a voice command, an old glasses build's double-tap) names a `zone:` record; only [resumeZones] ends one.
        val named = clears.filterNot(ZonePause::isZoneRecord).toSet()
        if (named.isEmpty()) return
        val t = now()
        dao.resumeNamed(named, atMs?.coerceAtMost(t) ?: t)
    }

    /** Pause zones: ends the named `zone:` records now (the zone logic only: the zone was left, disabled while outside, or removed while outside). Other ids are ignored. */
    suspend fun resumeZones(ids: Set<String>) {
        val zone = ids.filter(ZonePause::isZoneRecord).toSet()
        if (zone.isNotEmpty()) dao.resumeNamed(zone, now())
    }

    /** Pause zones: the active `zone:` records, as stored. */
    suspend fun zoneRecords(): List<PauseRecord> = stored().filter { ZonePause.isZoneRecord(it.pauseId) }

    /**
     * Quiet hours were turned off (BUG-R2-6): ends every stored QUIET_HOURS record now (the phone's and any union'd from
     * the glasses), by name like any Resume, so the glasses get the ids in `resumedPauseIds`. Returns the ids ended.
     */
    suspend fun endQuietHours(): Set<String> {
        val ids = stored().filter { it.origin == PauseOrigin.QUIET_HOURS }.map { it.pauseId }.toSet()
        resume(ids)
        return ids
    }

    /** Pause zones: true when [phoneMs] is inside a retained `zone:` interval (the frame gate: a zone beats the mark allowance). */
    override suspend fun zonePausedAt(phoneMs: Long): Boolean {
        ClockGuard.observe(now())
        if (ZoneHold.covers(phoneMs, phoneMs + ClockGuard.widenMs())) return true // a zone pause that could not be stored (held or ended) is still enforced
        active()
        // After a backward clock move, frame times mapped with the new offset sit [rollback] earlier than the stored interval starts: the check reaches that far (fail closed).
        return dao.countZoneOverlapping(phoneMs, phoneMs + ClockGuard.widenMs() + 1) > 0
    }

    /**
     * Pause zones: true when a `zone:` interval overlaps [fromMs, toMs] (a photo whose capture time is only known as a window, the phone camera): the same historical admission a glasses frame gets.
     * Fail closed: an unstored zone pause counts from its start on, and a clock that moved backwards widens the window.
     */
    suspend fun zoneOverlaps(fromMs: Long, toMs: Long): Boolean {
        ClockGuard.observe(now())
        if (ZoneHold.covers(fromMs - ClockGuard.widenMs(), toMs + ClockGuard.widenMs())) return true
        active()
        return dao.countZoneOverlapping(fromMs - ClockGuard.widenMs(), toMs + ClockGuard.widenMs() + 1) > 0
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
        if (ZoneHold.held) return true
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
