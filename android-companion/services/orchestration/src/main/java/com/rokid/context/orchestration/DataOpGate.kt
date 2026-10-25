package com.rokid.context.orchestration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Export vs delete, the simplest mutual exclusion there is (user ruling 2026-10-25). One process-wide [shared] gate.
 * - A DELETE (Forget a fact or a photo, Delete all, a retention pass, an outbox drain) takes the exclusive side with [delete]: if an export is running it WAITS for it,
 *   at most [waitMs] (30 s), then throws [ExportInProgress]; the caller fails the Forget visibly (a Forget is never silently dropped). Deletes do not exclude each other
 *   (they never did; each is its own transaction), so the gate is also re-entrant for them.
 * - An EXPORT takes the shared side with [export]: try-acquire only. It throws [DeleteRunning] at once while any delete runs OR waits (a waiting delete must not starve).
 * No other contention logic. [MemoryExport]'s generation check stays as a cheap defensive check for the one writer the gate does not cover (a memory edit).
 */
class DataOpGate(private val waitMs: Long = WAIT_MS) {
    /** A delete gave up waiting for a running export: nothing was deleted, try again. */
    class ExportInProgress : IOException("export in progress, try again")

    /** An export was refused because a delete is running: nothing was exported. */
    class DeleteRunning : IOException("busy: a delete is running")

    private val lock = Any()
    private var exporters = 0
    private var deleters = 0
    private var waiting = 0
    /** Bumped on every change so a waiting delete re-checks. */
    private val changed = MutableStateFlow(0)
    private fun bump() { changed.value = changed.value + 1 }

    /** Runs [block] as an export (shared side). Throws [DeleteRunning] without running it while a delete runs or waits. */
    suspend fun <T> export(block: suspend () -> T): T {
        synchronized(lock) {
            if (deleters > 0 || waiting > 0) throw DeleteRunning()
            exporters++
        }
        try {
            return block()
        } finally {
            synchronized(lock) { exporters-- }
            bump()
        }
    }

    /** Runs [block] as a delete (exclusive side): waits for a running export, bounded; throws [ExportInProgress] without running it when the wait ends. */
    suspend fun <T> delete(block: suspend () -> T): T {
        enterDelete()
        try {
            return block()
        } finally {
            synchronized(lock) { deleters-- }
            bump()
        }
    }

    private suspend fun enterDelete() {
        synchronized(lock) {
            if (exporters == 0) { deleters++; return }
            waiting++
        }
        var got = false
        try {
            withTimeoutOrNull(waitMs) {
                changed.first { synchronized(lock) { if (exporters == 0) { deleters++; got = true; true } else false } }
            }
        } finally {
            synchronized(lock) { waiting-- }
            bump()
        }
        if (!got) throw ExportInProgress()
    }

    companion object {
        const val WAIT_MS = 30_000L
        /** The one gate of the process (replaceable only by tests of this module, to shorten the wait). */
        @Volatile internal var shared = DataOpGate()
    }
}
