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
        return try {
            tmp.delete()
            converter.convert(dbFile, tmp, target, key())
            store.stage = target
            swap(target)
            store.failed = false
            target
        } catch (e: Exception) {
            Log.w(TAG, "encryption change failed (${e.javaClass.simpleName}); keeping the current database")
            if (!dbFile.exists() && old.exists()) old.renameTo(dbFile) // a failed swap rename: put the old database back
            tmp.delete()
            store.stage = null; store.target = null; store.failed = true
            current
        }
    }

    private fun swap(to: Boolean) {
        if (dbFile.exists()) check(dbFile.renameTo(old)) { "couldn't move the old database aside" }
        sidecars(dbFile).forEach { it.delete() } // the old file's journal/WAL must never be applied to the new one
        check(tmp.renameTo(dbFile)) { "couldn't move the converted database in place" }
        store.current = to; store.target = null; store.stage = null
        old.delete(); sidecars(old).forEach { it.delete() }
    }

    private fun recover() {
        val to = store.stage
        if (to == null) {
            tmp.delete()
            if (dbFile.exists()) old.delete()
            return
        }
        when {
            tmp.exists() -> swap(to)
            dbFile.exists() -> { store.current = to; store.target = null; store.stage = null; old.delete() }
            old.exists() -> { check(old.renameTo(dbFile)); store.stage = null } // nothing converted survived: keep the old file and mode
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
