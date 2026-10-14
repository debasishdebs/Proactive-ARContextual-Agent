package com.rokid.context.memory.db

import com.rokid.context.memory.db.EncryptionMigrator.FailReason
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * `sqlcipher_export` both ways. The encrypted side is always the connection's `main` (opened with the same binary
 * passphrase Room uses); the plaintext side is ATTACHed with `KEY ''`. user_version is copied and the FTS index is
 * rebuilt in the target, because the export renumbers `memory` rowids. Each step that fails is rethrown as a
 * [EncryptionMigrator.ConversionException] naming the step, so the log can say where without any message text.
 *
 * D-D4 round 3 (REBUILD_INDEX): `sqlcipher_export`, like VACUUM, creates the real tables in the target through the
 * parser but copies virtual tables (`memory_fts`, rootpage 0) and triggers as raw `sqlite_master` rows, so the
 * exporting connection's schema copy has no `memory_fts` and the rebuild there fails ("no such table"). The rebuild
 * therefore runs on a second connection, which reads the target's schema from disk (opened as `main`, or re-ATTACHed).
 */
object SqlCipherConverter : EncryptionMigrator.DbConverter {
    /** The few statements a conversion runs; [convert] backs it with the SQLCipher connection. */
    internal interface Sql : java.io.Closeable {
        fun exec(sql: String, vararg args: Any)
        fun queryInt(sql: String, vararg args: Any): Int
        fun query(sql: String, vararg args: Any)
    }

    override fun convert(src: File, dst: File, toEncrypted: Boolean, key: ByteArray) {
        step(FailReason.LOAD_LIBRARY) { SqlCipherLoader.ensureLoaded() }
        val cipherFile = if (toEncrypted) dst else src
        val plainFile = if (toEncrypted) src else dst
        // Only the first connection may create the encrypted file; the second one opens what the export wrote.
        run({ first ->
            val flags = if (toEncrypted && first) SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE
            Cipher(SQLiteDatabase.openDatabase(cipherFile.absolutePath, key, null, flags, null, null))
        }, plainFile.absolutePath, toEncrypted)
    }

    /** [open] gives a new connection to the encrypted file each call (`true` for the first one). */
    internal fun run(open: (first: Boolean) -> Sql, plainPath: String, toEncrypted: Boolean) {
        step(FailReason.OPEN) { open(true) }.use { sql ->
            step(FailReason.ATTACH) { sql.exec(ATTACH, plainPath) }
            if (toEncrypted) export(sql, from = "plain", to = "main") else export(sql, from = "main", to = "plain")
            step(FailReason.DETACH) { sql.exec(DETACH) }
        }
        step(FailReason.OPEN) { open(false) }.use { sql ->
            if (toEncrypted) {
                rebuild(sql, "main")
            } else {
                step(FailReason.ATTACH) { sql.exec(ATTACH, plainPath) }
                rebuild(sql, "plain")
                step(FailReason.DETACH) { sql.exec(DETACH) }
            }
        }
    }

    private fun export(sql: Sql, from: String, to: String) {
        val version = step(FailReason.READ_VERSION) { sql.queryInt("PRAGMA $from.user_version") }
        step(FailReason.EXPORT) { sql.query("SELECT sqlcipher_export('$to', '$from')") }
        step(FailReason.WRITE_VERSION) { sql.exec("PRAGMA $to.user_version = $version") }
    }

    /** FTS4 special command: the column is the table's own name; `memory` (the external content) is in [db] too. */
    private fun rebuild(sql: Sql, db: String) {
        step(FailReason.REBUILD_INDEX) { sql.exec("INSERT INTO $db.memory_fts(memory_fts) VALUES('rebuild')") }
    }

    private const val ATTACH = "ATTACH DATABASE ? AS plain KEY ''"
    private const val DETACH = "DETACH DATABASE plain"

    private inline fun <T> step(reason: FailReason, block: () -> T): T =
        try { block() } catch (e: Exception) { throw EncryptionMigrator.ConversionException(reason, e) }

    private class Cipher(private val db: SQLiteDatabase) : Sql {
        override fun exec(sql: String, vararg args: Any) { if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arrayOf(*args)) }
        override fun queryInt(sql: String, vararg args: Any): Int = db.rawQuery(sql, *args).use { c -> c.moveToFirst(); c.getInt(0) }
        override fun query(sql: String, vararg args: Any) { db.rawQuery(sql, *args).use { it.moveToFirst() } }
        override fun close() = db.close()
    }
}
