package com.rokid.context.memory.db

import android.os.Looper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Room calls `create` while building the database; the encryption mode is decided (and a pending migration applied)
 * only when Room first opens it, on its query thread. Any first open on the main thread is refused before the mode is
 * decided, so neither a migration nor the Keystore-backed passphrase read can run on main (Phase 2 carry, preflight F12).
 */
class DeferredOpenHelperFactory(
    private val encryptedAtOpen: () -> Boolean,
    private val passphrase: () -> ByteArray,
    private val onMainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() },
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = Deferred(configuration)

    private inner class Deferred(private val config: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {
        @Volatile private var delegate: SupportSQLiteOpenHelper? = null
        private var wal: Boolean? = null

        override val databaseName: String? get() = config.name
        override val writableDatabase: SupportSQLiteDatabase get() = open().writableDatabase
        override val readableDatabase: SupportSQLiteDatabase get() = open().readableDatabase

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(this) { wal = enabled; delegate?.setWriteAheadLoggingEnabled(enabled) ?: Unit }

        override fun close() = synchronized(this) { delegate?.close() ?: Unit }

        private fun open(): SupportSQLiteOpenHelper = delegate ?: synchronized(this) {
            delegate ?: run {
                // Before the decision (preflight F12): encryptedAtOpen may run a migration and read the Keystore key.
                check(!onMainThread()) { "Encrypted database opened on the main thread" }
                val encrypted = encryptedAtOpen()
                val factory = if (encrypted) { System.loadLibrary("sqlcipher"); SupportOpenHelperFactory(passphrase()) } else FrameworkSQLiteOpenHelperFactory()
                factory.create(config).also { h -> wal?.let(h::setWriteAheadLoggingEnabled); delegate = h }
            }
        }
    }
}
