package com.rokid.context.data.runtime

import android.content.Context
import android.util.Base64
import com.rokid.context.domain.queue.QueuePriority
import java.io.File
import java.util.UUID

data class QueueJobRecord(
    val id: String,
    val queue: QueuePriority,
    val state: String,
    val payload: String,
    val personaSnapshot: String,
    val enqueuedAtEpochMs: Long,
    val originalEnqueuedAtEpochMs: Long,
    val attemptCount: Int,
    val notBeforeEpochMs: Long,
    val ttlExpiryEpochMs: Long,
    val lastError: String,
)

data class QueueRuntimeSnapshot(
    val pendingByQueue: Map<QueuePriority, Int>,
    val runningByQueue: Map<QueuePriority, Int>,
    val deferredByQueue: Map<QueuePriority, Int>,
    val failedByQueue: Map<QueuePriority, Int>,
    val totalPending: Int,
    val totalDeferred: Int,
)

object QueueRuntimeStore {
    const val STATE_ENQUEUED = "ENQUEUED"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_SUCCEEDED = "SUCCEEDED"
    const val STATE_DEFERRED = "DEFERRED"
    const val STATE_FAILED = "FAILED"
    const val STATE_EXPIRED = "EXPIRED"

    private const val FILE_NAME = "queue_runtime.log"
    private val lock = Any()

    fun enqueue(
        context: Context,
        queue: QueuePriority,
        payload: String,
        personaSnapshot: String,
        ttlHours: Int = 24,
        enqueueAtEpochMs: Long = System.currentTimeMillis(),
        notBeforeEpochMs: Long = enqueueAtEpochMs,
    ): QueueJobRecord {
        synchronized(lock) {
            val file = file(context)
            val now = enqueueAtEpochMs
            val ttlMs = ttlHours.coerceAtLeast(1) * 60L * 60L * 1000L
            val record = QueueJobRecord(
                id = "${queue.name.lowercase()}-${UUID.randomUUID()}",
                queue = queue,
                state = STATE_ENQUEUED,
                payload = payload,
                personaSnapshot = personaSnapshot,
                enqueuedAtEpochMs = now,
                originalEnqueuedAtEpochMs = now,
                attemptCount = 0,
                notBeforeEpochMs = notBeforeEpochMs,
                ttlExpiryEpochMs = now + ttlMs,
                lastError = "",
            )
            append(file, record)
            return record
        }
    }

    /** Settings → Memory › Delete all: every job goes, payloads included (they carry fact and user text). */
    fun clearAll(context: Context) {
        synchronized(lock) { file(context).delete() }
    }

    fun claimNextReady(context: Context, nowEpochMs: Long = System.currentTimeMillis()): QueueJobRecord? {
        synchronized(lock) {
            val all = readAll(context)
            if (all.isEmpty()) return null

            val paused = !AppConfigStore.isPipelineEnabled(context)
            val normalized = all.map { normalizeState(it, nowEpochMs, paused) }
                .map {
                    if (it.state == STATE_DEFERRED && it.notBeforeEpochMs <= nowEpochMs) {
                        it.copy(state = STATE_ENQUEUED, lastError = "")
                    } else {
                        it
                    }
                }
                .toMutableList()

            val candidate = QueuePriority.arbitrate(normalized.map { it.queue })
                .asSequence()
                .flatMap { priority ->
                    normalized
                        .asSequence()
                        .filter { it.queue == priority }
                        .filter { it.state == STATE_ENQUEUED }
                        .filter { it.notBeforeEpochMs <= nowEpochMs }
                        .sortedBy { it.enqueuedAtEpochMs }
                }
                .firstOrNull()

            if (candidate == null) {
                writeAll(context, normalized)
                return null
            }

            val idx = normalized.indexOfFirst { it.id == candidate.id }
            if (idx >= 0) {
                normalized[idx] = candidate.copy(state = STATE_RUNNING)
                writeAll(context, normalized)
                return normalized[idx]
            }
            return null
        }
    }

    fun markSucceeded(context: Context, id: String) = mutate(context, id) {
        copy(state = STATE_SUCCEEDED, lastError = "")
    }

    fun markFailed(context: Context, id: String, error: String) = mutate(context, id) {
        copy(state = STATE_FAILED, lastError = sanitize(error))
    }

    fun markDeferred(
        context: Context,
        id: String,
        error: String,
        backoffMs: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
    ) = mutate(context, id) {
        copy(
            state = STATE_DEFERRED,
            attemptCount = attemptCount + 1,
            notBeforeEpochMs = nowEpochMs + backoffMs.coerceAtLeast(15_000L),
            lastError = sanitize(error),
        )
    }

    /**
     * Not an attempt (Phase 2 carry): battery/policy deferrals and WorkManager cancellations keep [QueueJobRecord.attemptCount],
     * so only real RETRY outcomes count towards RetryPolicy.MAX_ATTEMPTS; the 24 h TTL still applies.
     */
    fun markPostponed(context: Context, id: String, note: String, delayMs: Long, nowEpochMs: Long = System.currentTimeMillis()) = mutate(context, id) {
        copy(state = STATE_DEFERRED, notBeforeEpochMs = nowEpochMs + delayMs.coerceAtLeast(15_000L), lastError = sanitize(note))
    }

    /**
     * Carry C1, called on resume: every live row's TTL moves out by the paused time it lived through,
     * `min(byMs, now − enqueued)`, so a job queued during the pause gets back only its own share.
     */
    fun extendTtl(context: Context, byMs: Long, nowEpochMs: Long = System.currentTimeMillis()) {
        if (byMs <= 0L) return
        synchronized(lock) {
            val rows = readAll(context)
            if (rows.isEmpty()) return
            writeAll(
                context,
                rows.map { r ->
                    if (isTerminal(r)) r else r.copy(ttlExpiryEpochMs = r.ttlExpiryEpochMs + byMs.coerceAtMost((nowEpochMs - r.originalEnqueuedAtEpochMs).coerceAtLeast(0L)))
                },
            )
        }
    }

    /** Fix M2, called when a pause starts: rows already past their TTL expire now, before expiry is frozen. */
    fun expireOverdue(context: Context, nowEpochMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val rows = readAll(context)
            val normalized = rows.map { normalizeState(it, nowEpochMs, paused = false) }
            if (normalized != rows) writeAll(context, normalized)
        }
    }

    /** Rows of [queue] that are not finished yet (queued, running or deferred). */
    fun live(context: Context, queue: QueuePriority): List<QueueJobRecord> {
        synchronized(lock) {
            return readAll(context).filter { it.queue == queue && !isTerminal(it) }
        }
    }

    fun snapshot(context: Context, nowEpochMs: Long = System.currentTimeMillis()): QueueRuntimeSnapshot {
        synchronized(lock) {
            val paused = !AppConfigStore.isPipelineEnabled(context)
            val rows = readAll(context).map { normalizeState(it, nowEpochMs, paused) }
            writeAll(context, rows)

            val pendingByQueue = QueuePriority.entries.associateWith { q ->
                rows.count { it.queue == q && (it.state == STATE_ENQUEUED || it.state == STATE_RUNNING) }
            }
            val runningByQueue = QueuePriority.entries.associateWith { q ->
                rows.count { it.queue == q && it.state == STATE_RUNNING }
            }
            val deferredByQueue = QueuePriority.entries.associateWith { q ->
                rows.count { it.queue == q && it.state == STATE_DEFERRED }
            }
            val failedByQueue = QueuePriority.entries.associateWith { q ->
                rows.count { it.queue == q && it.state == STATE_FAILED }
            }

            return QueueRuntimeSnapshot(
                pendingByQueue = pendingByQueue,
                runningByQueue = runningByQueue,
                deferredByQueue = deferredByQueue,
                failedByQueue = failedByQueue,
                totalPending = pendingByQueue.values.sum(),
                totalDeferred = deferredByQueue.values.sum(),
            )
        }
    }

    fun recent(context: Context, limit: Int = 80): List<QueueJobRecord> {
        synchronized(lock) {
            return readAll(context)
                .sortedByDescending { it.enqueuedAtEpochMs }
                .take(limit.coerceAtLeast(1))
        }
    }

    fun purgeSynthetic(context: Context) {
        synchronized(lock) {
            val kept = readAll(context).filter { it.payload.contains("\"source\":\"rokid", ignoreCase = true) || it.queue != QueuePriority.FRAME_INGEST }
            writeAll(context, kept)
        }
    }

    private fun mutate(
        context: Context,
        id: String,
        update: QueueJobRecord.() -> QueueJobRecord,
    ) {
        synchronized(lock) {
            val rows = readAll(context).toMutableList()
            val idx = rows.indexOfFirst { it.id == id }
            if (idx < 0) return
            rows[idx] = rows[idx].update()
            writeAll(context, rows)
        }
    }

    private fun isTerminal(record: QueueJobRecord) =
        record.state == STATE_SUCCEEDED || record.state == STATE_FAILED || record.state == STATE_EXPIRED

    /** While paused nothing expires (carry C1); the paused time is given back on resume by [extendTtl]. */
    private fun normalizeState(record: QueueJobRecord, nowEpochMs: Long, paused: Boolean): QueueJobRecord {
        if (paused || isTerminal(record)) {
            return record
        }
        return if (nowEpochMs >= record.ttlExpiryEpochMs) {
            record.copy(state = STATE_EXPIRED, lastError = "TTL expired")
        } else {
            record
        }
    }

    private fun readAll(context: Context): List<QueueJobRecord> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parse(it) }
    }

    private fun writeAll(context: Context, rows: List<QueueJobRecord>) {
        val file = file(context)
        file.parentFile?.mkdirs()
        file.writeText("")
        rows.forEach { append(file, it) }
    }

    private fun append(file: File, row: QueueJobRecord) {
        file.parentFile?.mkdirs()
        if (!file.exists()) file.createNewFile()
        val payload = encode(row.payload)
        val persona = encode(row.personaSnapshot)
        val error = encode(row.lastError)
        file.appendText(
            listOf(
                row.id,
                row.queue.name,
                row.state,
                row.enqueuedAtEpochMs.toString(),
                row.originalEnqueuedAtEpochMs.toString(),
                row.attemptCount.toString(),
                row.notBeforeEpochMs.toString(),
                row.ttlExpiryEpochMs.toString(),
                persona,
                payload,
                error,
            ).joinToString("\t") + "\n",
        )
    }

    private fun parse(line: String): QueueJobRecord? {
        val parts = line.split("\t")
        if (parts.size < 11) return null
        return QueueJobRecord(
            id = parts[0],
            queue = runCatching { QueuePriority.valueOf(parts[1]) }.getOrNull() ?: return null,
            state = parts[2],
            enqueuedAtEpochMs = parts[3].toLongOrNull() ?: return null,
            originalEnqueuedAtEpochMs = parts[4].toLongOrNull() ?: return null,
            attemptCount = parts[5].toIntOrNull() ?: 0,
            notBeforeEpochMs = parts[6].toLongOrNull() ?: 0L,
            ttlExpiryEpochMs = parts[7].toLongOrNull() ?: 0L,
            personaSnapshot = decode(parts[8]),
            payload = decode(parts[9]),
            lastError = decode(parts[10]),
        )
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun encode(value: String): String {
        return Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decode(value: String): String {
        return runCatching {
            String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun sanitize(value: String): String = value.replace("\n", " ").replace("\t", " ").take(220)
}
