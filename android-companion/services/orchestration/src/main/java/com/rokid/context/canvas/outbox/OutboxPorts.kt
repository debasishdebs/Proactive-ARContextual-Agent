package com.rokid.context.canvas.outbox

/** What one cleanup step achieved. [NOT_REACHABLE]: the device or link is not there yet, so the row stays and nothing is charged against it. */
enum class CleanupResult { DONE, NOT_REACHABLE, FAILED }

/** A pending `cleanup_outbox` row as the drainer sees it (payload text only: ids, paths and ranges, never user content). */
data class OutboxRow(val id: Long, val target: String, val payloadJson: String)

/** Failed attempts of one row and when the last one ran (phone clock). Content-free. */
data class OutboxAttempt(val count: Int, val lastAtMs: Long)

/** The durable outbox and the attempt book. */
interface OutboxStore {
    /** At most [limit] pending rows, phone files first then oldest, never one of [excludeIds] (rows still in their backoff). The drainer never loads the whole table. */
    suspend fun due(limit: Int, excludeIds: Collection<Long>): List<OutboxRow>
    /** How many rows are pending. */
    suspend fun count(): Int
    /** Which of [ids] are still pending. */
    suspend fun existing(ids: Collection<Long>): Set<Long>
    /** Keeps the newest [keep] rows of [target] and drops the older ones; returns how many were dropped. */
    suspend fun trim(target: String, keep: Int): Int
    /** Deletes the row. Called only after the deletion it stands for was confirmed. */
    suspend fun remove(id: Long)
    suspend fun attempts(): Map<Long, OutboxAttempt>
    suspend fun saveAttempts(m: Map<Long, OutboxAttempt>)
}

/** Deletes a phone file. [CleanupResult.DONE] only when the file is confirmed gone (deleted now or never there). Never throws for an ordinary failure. */
interface PhoneFilePort {
    suspend fun deleteConfirmed(path: String): CleanupResult
}

/**
 * The glasses side (frames, queued marks and traces in a phone-clock range, everything on Delete all). The TRANSPORT is not built yet: production binds [NotReachableGlasses].
 * Every call must be idempotent on the glasses (already gone is DONE) and DONE means the glasses confirmed it.
 */
interface GlassesCleanupPort {
    /** A cheap probe, asked at most once per drain run; false keeps every glasses row untouched. */
    suspend fun reachable(): Boolean
    suspend fun deleteFrame(wireFrameId: String): CleanupResult
    suspend fun deleteTrace(traceId: String): CleanupResult
    suspend fun deleteRange(fromMs: Long, toMs: Long): CleanupResult
    suspend fun wipeAll(): CleanupResult
}

/** The laptop helper side (copies the helper holds). Not built yet: production binds [NotReachableHelper]. Delete all is the only row that reaches it today. */
interface HelperCleanupPort {
    suspend fun reachable(): Boolean
    suspend fun wipeAll(): CleanupResult
}

/** The glasses port until the transport exists: every row for it stays and is retried later. */
object NotReachableGlasses : GlassesCleanupPort {
    override suspend fun reachable() = false
    override suspend fun deleteFrame(wireFrameId: String) = CleanupResult.NOT_REACHABLE
    override suspend fun deleteTrace(traceId: String) = CleanupResult.NOT_REACHABLE
    override suspend fun deleteRange(fromMs: Long, toMs: Long) = CleanupResult.NOT_REACHABLE
    override suspend fun wipeAll() = CleanupResult.NOT_REACHABLE
}

/** The helper port until the transport exists. */
object NotReachableHelper : HelperCleanupPort {
    override suspend fun reachable() = false
    override suspend fun wipeAll() = CleanupResult.NOT_REACHABLE
}
