package com.rokid.context.memory.db

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Applies a pending encryption change before the database is opened (Settings → Memory, spec §6.1). Steps:
 * convert into `<db>.migrating` → stage = target → rename db → `<db>.old`, delete the db's journal/WAL, rename
 * migrating → db → flags → delete old. [recover] finishes or rolls back whichever step a crash interrupted.
 * Never throws for a failed conversion: the old database and mode stay, and [ModeStore.failed] is set for the UI.
 */
class EncryptionMigrator(
    private val dbFile: File,
    private val store: ModeStore,
    private val converter: DbConverter,
    /** Atomic same-directory rename; injectable so a test can fail one step of the swap. */
    private val rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
    private val key: () -> ByteArray,
) {
    interface ModeStore {
        var current: Boolean?
        var target: Boolean?
        /** The target an export has completed for; null = no migration in progress. */
        var stage: Boolean?
        var failed: Boolean
    }

    fun interface DbConverter { fun convert(src: File, dst: File, toEncrypted: Boolean, key: ByteArray) }

    /** Fixed, key-free codes for where an encryption change failed; the only failure detail ever logged with the class. */
    enum class FailReason { KEY_READ, LOAD_LIBRARY, OPEN, ATTACH, READ_VERSION, EXPORT, WRITE_VERSION, REBUILD_INDEX, DETACH, CONVERT, SWAP }

    /** A failed conversion step. Carries no message: the cause's text may hold a path or SQL, so it is never logged. */
    class ConversionException(val reason: FailReason, cause: Throwable) : Exception(null as String?, cause)

    private val tmp = File(dbFile.path + ".migrating")
    private val old = File(dbFile.path + ".old")

    @Synchronized
    fun prepare(requestedForNew: () -> Boolean): Boolean {
        recover()
        if (!dbFile.exists()) {
            val mode = store.target ?: store.current ?: requestedForNew()
            store.current = mode; store.target = null
            return mode
        }
        val current = store.current ?: (!isPlaintextSqlite(dbFile)).also { store.current = it }
        val target = store.target ?: return current
        if (target == current) { store.target = null; return current }
        var inPlace = false
        var phase = FailReason.KEY_READ
        return try {
            discardTmp()
            val k = key()
            phase = FailReason.CONVERT
            try { converter.convert(dbFile, tmp, target, k) } finally { k.fill(0) } // P8 D4: our copy, zeroed once used
            phase = FailReason.SWAP
            store.stage = target
            swap(target) { inPlace = true }
            store.failed = false
            target
        } catch (e: Exception) {
            if (inPlace) {
                // The converted file is the database now: never roll the mode back. stage (committed before the renames)
                // is only cleared after current = target is committed, so the next recover() can always finish the flags.
                Log.w(TAG, "encryption change applied, flags not yet saved (${e.javaClass.simpleName}); finishing at next open")
                runCatching { recover() }
                return target
            }
            // BUG-R2-2: class + fixed reason code only; a message may carry a path or SQL, so it is never logged.
            val reason = (e as? ConversionException)?.reason ?: phase
            val cls = ((e as? ConversionException)?.cause ?: e).javaClass.simpleName
            Log.w(TAG, "encryption change failed ($cls, reason=$reason); keeping the current database")
            if (!dbFile.exists() && old.exists()) rename(old, dbFile) // a failed swap rename: put the old database back
            discardTmp()
            store.stage = null; store.target = null; store.failed = true
            current
        }
    }

    private fun discardTmp() { tmp.delete(); sidecars(tmp).forEach { it.delete() } }

    private fun swap(to: Boolean, onInPlace: () -> Unit = {}) {
        if (dbFile.exists()) check(rename(dbFile, old)) { "couldn't move the old database aside" }
        sidecars(dbFile).forEach { it.delete() } // the old file's journal/WAL must never be applied to the new one
        check(rename(tmp, dbFile)) { "couldn't move the converted database in place" }
        onInPlace()
        sidecars(tmp).forEach { it.delete() }
        store.current = to; store.target = null; store.stage = null
        old.delete(); sidecars(old).forEach { it.delete() }
    }

    private fun recover() {
        val to = store.stage
        if (to == null) {
            discardTmp()
            if (dbFile.exists()) old.delete()
            return
        }
        when {
            tmp.exists() -> swap(to)
            dbFile.exists() -> { store.current = to; store.target = null; store.stage = null; old.delete() }
            old.exists() -> { check(rename(old, dbFile)); store.stage = null } // nothing converted survived: keep the old file and mode
            else -> store.stage = null
        }
    }

    private fun sidecars(f: File) = listOf("-wal", "-shm", "-journal").map { File(f.path + it) }

    companion object {
        private const val TAG = "EncryptionMigrator"
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

        /** True only for a plaintext SQLite file; anything else (including empty/short) is treated as SQLCipher. */
        fun isPlaintextSqlite(file: File): Boolean = runCatching {
            file.inputStream().use { ins ->
                val buf = ByteArray(SQLITE_HEADER.size)
                var n = 0
                while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                n == buf.size && buf.contentEquals(SQLITE_HEADER)
            }
        }.getOrDefault(false)
    }
}

/** Same prefs/keys the Phase 2 MemoryGraph used for the mode flag, so existing installs keep their mode. */
class PrefsModeStore(context: Context) : EncryptionMigrator.ModeStore {
    private val prefs = context.applicationContext.getSharedPreferences("memory_graph", Context.MODE_PRIVATE)
    private fun bool(k: String): Boolean? = if (prefs.contains(k)) prefs.getBoolean(k, false) else null
    private fun set(k: String, v: Boolean?) { check(prefs.edit().apply { if (v == null) remove(k) else putBoolean(k, v) }.commit()) { "couldn't save $k" } }
    override var current: Boolean? get() = bool("db_encrypted"); set(v) = set("db_encrypted", v)
    override var target: Boolean? get() = bool("db_encrypted_target"); set(v) = set("db_encrypted_target", v)
    override var stage: Boolean? get() = bool("db_migration_stage"); set(v) = set("db_migration_stage", v)
    override var failed: Boolean get() = prefs.getBoolean("db_migration_failed", false); set(v) = set("db_migration_failed", v)
}
