package com.rokid.context.memory.db

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import java.util.UUID

/**
 * The persisted deletion epoch (S57), in the phone prefs file [PREFS] (`deletion_epoch`, excluded from backup and device transfer: a restored
 * epoch would let another install's data pass its fence). Every write is a synchronous `commit()`: [commitWipe] returns only once the new counter
 * is on disk, and throws [IOException] when it cannot be (Delete all then deletes nothing: failing closed beats deleting without a record).
 */
class DeletionEpochStore(private val prefs: SharedPreferences, private val newInstallId: () -> String = { UUID.randomUUID().toString() }) {
    /** The current epoch. The install id is created (and committed) on first use and never changes afterwards. */
    fun read(): DeletionEpoch = synchronized(LOCK) {
        var id = prefs.getString(KEY_INSTALL, null)?.takeIf { it.isNotBlank() }
        if (id == null) {
            id = newInstallId()
            // If the first write fails the id still has to be stable for this process: it is re-created on every read otherwise.
            if (!prefs.edit().putString(KEY_INSTALL, id).commit()) id = fallbackId ?: id.also { fallbackId = it }
        }
        DeletionEpoch(id, prefs.getLong(KEY_COUNTER, 0L), prefs.getLong(KEY_WIPED_AT, 0L))
    }

    /**
     * Delete all's first act: `counter + 1` and `wipedAtMs = max(now, previous)` are on disk BEFORE anything is deleted, so a crash after this call
     * (or a power loss mid-wipe) leaves a durable record that everything recorded under the old counter is stale. Throws [IOException] if not committed.
     */
    fun commitWipe(nowMs: Long): DeletionEpoch = synchronized(LOCK) {
        val old = read()
        val next = DeletionEpoch(old.installId, old.counter + 1, maxOf(nowMs, old.wipedAtMs))
        if (!prefs.edit().putLong(KEY_COUNTER, next.counter).putLong(KEY_WIPED_AT, next.wipedAtMs).commit()) throw IOException("deletion epoch not committed")
        next
    }

    companion object {
        const val PREFS = "deletion_epoch"
        private const val KEY_INSTALL = "installId"
        private const val KEY_COUNTER = "counter"
        private const val KEY_WIPED_AT = "wipedAtMs"
        private val LOCK = Any()
        @Volatile private var fallbackId: String? = null

        fun of(context: Context): DeletionEpochStore = DeletionEpochStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
