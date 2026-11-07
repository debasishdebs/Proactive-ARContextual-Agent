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
    open class ExportInProgress(message: String = "export in progress, try again") : IOException(message)

    /** A delete gave up waiting for a running write (a canvas Save): nothing was deleted, try again (a save is short). */
    class WriteInProgress : ExportInProgress("save in progress, try again")

    /** An export was refused because a delete is running: nothing was exported. */
    open class DeleteRunning(message: String = "busy: a delete is running") : IOException(message)

    /** An export was refused because a write (a canvas Save) is running: nothing was exported, try again. */
    class WriteRunning : DeleteRunning("busy: a save is running")

    /** A write (a canvas Save) gave up waiting for a running export ([byExport]) or delete: nothing was written, try again. */
    class WriteBlocked(val byExport: Boolean) : IOException(if (byExport) "export in progress, try again" else "delete in progress, try again")

    private val lock = Any()
    private var exporters = 0
    private var deleters = 0
    private var waiting = 0
    /** Writes that RESERVED the shared side (a Save holds it for its whole run); they do not exclude each other. */
    private var writers = 0
    /** Bumped on every change so a waiting delete re-checks. */
    private val changed = MutableStateFlow(0)
    private fun bump() { changed.value = changed.value + 1 }

    /** Runs [block] as an export (shared side). Throws [DeleteRunning] without running it while a delete runs or waits. */
    suspend fun <T> export(block: suspend () -> T): T {
        synchronized(lock) {
            if (deleters > 0 || waiting > 0) throw DeleteRunning()
            if (writers > 0) throw WriteRunning()
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

    /**
     * Runs [block] as a WRITE of user-requested memories (the canvas Save): it waits, at most [waitMs], until neither an export nor a delete runs (or waits), then RESERVES a writer slot in the same
     * step and holds it for the whole of [block]: an export is refused ([WriteRunning]) and a delete waits (bounded, then [WriteInProgress]) while a write runs. Past the wait it throws [WriteBlocked]
     * naming which one it was waiting for, and [block] never runs. Writes do not exclude each other. A waiting delete stops NEW writes from starting, so writes cannot starve a delete.
     */
    suspend fun <T> write(block: suspend () -> T): T {
        var byExport = false
        var reserved = false // guarded by [lock]; set in the SAME step as the admission check, released in the one finally below, whatever happens in between
        fun tryReserve() = synchronized(lock) {
            byExport = exporters > 0
            (exporters == 0 && deleters == 0 && waiting == 0).also { if (it) { writers++; reserved = true; testInReserve?.invoke() } }
        }
        try {
            if (!tryReserve()) {
                val ok = withTimeoutOrNull(waitMs) { changed.first { tryReserve() }; true } == true
                // a timeout that landed right after the slot was taken still owns the slot: it runs (the finally releases it)
                if (!ok && !synchronized(lock) { reserved }) throw WriteBlocked(byExport)
            }
            return block()
        } finally {
            synchronized(lock) { if (reserved) { writers--; reserved = false } }
            bump()
        }
    }

    /** True while an export, delete or write runs or a delete waits: a read that must not overlap them (the retrieve-debug route) fails closed. */
    fun busy(): Boolean = synchronized(lock) { exporters > 0 || deleters > 0 || waiting > 0 || writers > 0 }

    /** Test seam: runs right after a write took its slot, inside the admission step (a test cancels there). Null in production. */
    @Volatile internal var testInReserve: (() -> Unit)? = null

    /** Test seam: runs inside the wait's predicate once no export is running (a test cancels the delete exactly there). Null in production. */
    @Volatile internal var testInPredicate: (() -> Unit)? = null

    /**
     * Cancellation safe: the wait only READS the state; the acquisition is one non-suspending step in the `finally`, taken only when the wait completed normally, so no
     * cancellation or timeout can land between taking `deleters` and the caller's release (a leaked `deleters` would refuse every later export).
     * While `waiting > 0` no export can start, so the state the predicate saw still holds in the `finally`.
     */
    private suspend fun enterDelete() {
        synchronized(lock) {
            if (exporters == 0 && writers == 0) { deleters++; return }
            waiting++
        }
        var done = false
        var got = false
        var byWrite = false
        try {
            done = withTimeoutOrNull(waitMs) {
                changed.first { synchronized(lock) { (exporters == 0 && writers == 0).also { if (it) testInPredicate?.invoke() } } }
                true
            } == true
        } finally {
            synchronized(lock) {
                waiting--
                if (done && exporters == 0 && writers == 0) { deleters++; got = true }
                byWrite = exporters == 0 && writers > 0
            }
            bump()
        }
        if (!got) throw if (byWrite) WriteInProgress() else ExportInProgress()
    }

    companion object {
        const val WAIT_MS = 30_000L
        /** The one gate of the process (replaceable only by tests of this module, to shorten the wait). */
        @Volatile var shared = DataOpGate()
            internal set
    }
}
