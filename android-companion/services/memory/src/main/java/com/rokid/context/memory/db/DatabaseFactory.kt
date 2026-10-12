package com.rokid.context.memory.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    const val NAME = "context.db"

    /** [encrypted] is decided once at first creation and must never change for an existing file (migration: Phase 5). */
    fun build(context: Context, encrypted: Boolean, passphrase: () -> ByteArray): ContextDatabase {
        val builder = Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
        if (encrypted) {
            System.loadLibrary("sqlcipher")
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase()))
        }
        return builder.build()
    }

    fun inMemory(context: Context): ContextDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, ContextDatabase::class.java).allowMainThreadQueries().build()
}
