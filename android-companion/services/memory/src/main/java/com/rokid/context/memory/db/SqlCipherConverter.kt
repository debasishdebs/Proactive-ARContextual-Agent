package com.rokid.context.memory.db

import com.rokid.context.memory.db.EncryptionMigrator.FailReason
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * `sqlcipher_export` both ways. The encrypted side is always the connection's `main` (opened with the same binary
 * passphrase Room uses); the plaintext side is ATTACHed with `KEY ''`. user_version is copied and the FTS index is
 * rebuilt in the target, because the export renumbers `memory` rowids. Each step that fails is rethrown as a
 * [EncryptionMigrator.ConversionException] naming the step, so the log can say where without any message text.
 */
object SqlCipherConverter : EncryptionMigrator.DbConverter {
    /** The few statements a conversion runs; [convert] backs it with the SQLCipher connection. */
    internal interface Sql {
        fun exec(sql: String, vararg args: Any)
        fun queryInt(sql: String, vararg args: Any): Int
        fun query(sql: String, vararg args: Any)
    }

    override fun convert(src: File, dst: File, toEncrypted: Boolean, key: ByteArray) {
        step(FailReason.LOAD_LIBRARY) { SqlCipherLoader.ensureLoaded() }
        val cipherFile = if (toEncrypted) dst else src
        val plainFile = if (toEncrypted) src else dst
        val flags = if (toEncrypted) SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY else SQLiteDatabase.OPEN_READWRITE
        val db = step(FailReason.OPEN) { SQLiteDatabase.openDatabase(cipherFile.absolutePath, key, null, flags, null, null) }
        db.use { run(Cipher(it), plainFile.absolutePath, toEncrypted) }
    }

    internal fun run(sql: Sql, plainPath: String, toEncrypted: Boolean) {
        step(FailReason.ATTACH) { sql.exec("ATTACH DATABASE ? AS plain KEY ''", plainPath) }
        if (toEncrypted) export(sql, from = "plain", to = "main") else export(sql, from = "main", to = "plain")
        step(FailReason.DETACH) { sql.exec("DETACH DATABASE plain") }
    }

    private fun export(sql: Sql, from: String, to: String) {
        val version = step(FailReason.READ_VERSION) { sql.queryInt("PRAGMA $from.user_version") }
        step(FailReason.EXPORT) { sql.query("SELECT sqlcipher_export('$to', '$from')") }
        step(FailReason.WRITE_VERSION) { sql.exec("PRAGMA $to.user_version = $version") }
        step(FailReason.REBUILD_INDEX) { sql.exec("INSERT INTO $to.memory_fts(memory_fts) VALUES('rebuild')") }
    }

    private inline fun <T> step(reason: FailReason, block: () -> T): T =
        try { block() } catch (e: Exception) { throw EncryptionMigrator.ConversionException(reason, e) }

    private class Cipher(private val db: SQLiteDatabase) : Sql {
        override fun exec(sql: String, vararg args: Any) { if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, arrayOf(*args)) }
        override fun queryInt(sql: String, vararg args: Any): Int = db.rawQuery(sql, *args).use { c -> c.moveToFirst(); c.getInt(0) }
        override fun query(sql: String, vararg args: Any) { db.rawQuery(sql, *args).use { it.moveToFirst() } }
    }
}
