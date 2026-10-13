package com.rokid.context.memory.db

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * `sqlcipher_export` both ways. The encrypted side is always the connection's `main` (opened with the same binary
 * passphrase Room uses); the plaintext side is ATTACHed with `KEY ''`. user_version is copied and the FTS index is
 * rebuilt in the target, because the export renumbers `memory` rowids.
 */
object SqlCipherConverter : EncryptionMigrator.DbConverter {
    override fun convert(src: File, dst: File, toEncrypted: Boolean, key: ByteArray) {
        SqlCipherLoader.ensureLoaded()
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        if (toEncrypted) {
            SQLiteDatabase.openDatabase(dst.absolutePath, key, null, flags, null, null).use { db ->
                db.execSQL("ATTACH DATABASE ? AS plain KEY ''", arrayOf<Any>(src.absolutePath))
                export(db, from = "plain", to = "main")
                db.execSQL("DETACH DATABASE plain")
            }
        } else {
            SQLiteDatabase.openDatabase(src.absolutePath, key, null, SQLiteDatabase.OPEN_READWRITE, null, null).use { db ->
                db.execSQL("ATTACH DATABASE ? AS plain KEY ''", arrayOf<Any>(dst.absolutePath))
                export(db, from = "main", to = "plain")
                db.execSQL("DETACH DATABASE plain")
            }
        }
    }

    private fun export(db: SQLiteDatabase, from: String, to: String) {
        val version = db.rawQuery("PRAGMA $from.user_version", arrayOf<Any>()).use { c -> c.moveToFirst(); c.getInt(0) }
        db.rawQuery("SELECT sqlcipher_export('$to', '$from')", arrayOf<Any>()).use { it.moveToFirst() }
        db.execSQL("PRAGMA $to.user_version = $version")
        db.execSQL("INSERT INTO $to.memory_fts(memory_fts) VALUES('rebuild')")
    }
}
