package com.rokid.context.memory.db

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.UUID

/**
 * The persisted deletion epoch (S57), in the phone prefs file [PREFS] (`deletion_epoch`, excluded from backup and device transfer: a restored
 * epoch would let another install's data pass its fence). Every write is a synchronous `commit()`: [commitWipe] returns only once the new counter
 * is on disk, and throws [IOException] when it cannot be (Delete all then deletes nothing: failing closed beats deleting without a record).
 *
 * The COUNTER is the real fence: every derived commit compares install id and counter, and no clock is involved. `wipedAtMs` only backs the occurrence-time
 * fence for marks (a mark that happened before the wipe but arrives after it), so it is the plain commit-time clock and is NOT held monotonic: holding it
 * at a larger earlier value would let one wipe taken while the clock ran far ahead drop every later real mark until the clock caught up. A clock that is
 * wrong at the wipe can therefore only misjudge marks within that error, never a stale commit (that is refused by the counter).
 */
class DeletionEpochStore(private val prefs: SharedPreferences, private val newInstallId: () -> String = { UUID.randomUUID().toString() }) {
    /** The current epoch. The install id is created (and committed) on first use and never changes afterwards. */
    fun read(): DeletionEpoch = synchronized(LOCK) {
        var id = prefs.getString(KEY_INSTALL, null)?.takeIf { it.isNotBlank() }
        if (id == null) {
            id = newInstallId()
            // If the first write fails the id still has to be stable for this process: it would be re-created on every read otherwise.
            if (!prefs.edit().putString(KEY_INSTALL, id).commit()) id = fallbackIds.getOrPut(prefs) { id }
        }
        DeletionEpoch(id, prefs.getLong(KEY_COUNTER, 0L), prefs.getLong(KEY_WIPED_AT, 0L))
    }

    /**
     * Delete all's first act: `counter + 1` and `wipedAtMs = now` are on disk BEFORE anything is deleted, so a crash after this call
     * (or a power loss mid-wipe) leaves a durable record that everything recorded under the old counter is stale. Throws [IOException] if not committed.
     */
    fun commitWipe(nowMs: Long): DeletionEpoch = synchronized(LOCK) {
        val old = read()
        val next = DeletionEpoch(old.installId, old.counter + 1, nowMs)
        if (!prefs.edit().putLong(KEY_COUNTER, next.counter).putLong(KEY_WIPED_AT, next.wipedAtMs).putLong(KEY_PENDING, next.counter).commit()) throw IOException("deletion epoch not committed")
        next
    }

    /**
     * True from the epoch commit of a Delete all until its cleanup finished completely (the marker is written in the SAME commit as the new counter, so
     * there is no instant where the counter moved but a crash would forget the cleanup). The start-up recovery finishes a pending wipe.
     */
    fun wipePending(): Boolean = synchronized(LOCK) { prefs.getLong(KEY_PENDING, 0L) > 0L }

    /** Called only when every deletion succeeded. False when it could not be written (the next start retries the cleanup, which is idempotent). */
    fun clearWipePending(): Boolean = synchronized(LOCK) { prefs.edit().remove(KEY_PENDING).commit() }

    companion object {
        const val PREFS = "deletion_epoch"
        private const val KEY_PENDING = "wipePending"
        private const val KEY_INSTALL = "installId"
        private const val KEY_COUNTER = "counter"
        private const val KEY_WIPED_AT = "wipedAtMs"
        private val LOCK = Any()
        /** Process-lifetime ids for a prefs file whose first write failed (keyed by the prefs object, which is a per-file singleton in a real app). */
        private val fallbackIds = java.util.WeakHashMap<SharedPreferences, String>()

        fun of(context: Context): DeletionEpochStore = DeletionEpochStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
