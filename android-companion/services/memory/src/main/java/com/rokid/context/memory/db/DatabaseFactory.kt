package com.rokid.context.memory.db

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    const val NAME = "context.db"

    /** Fixed mode, for instrumented tests and tools; the app uses [buildDeferred]. */
    fun build(context: Context, encrypted: Boolean, passphrase: () -> ByteArray): ContextDatabase {
        val builder = Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
        if (encrypted) {
            System.loadLibrary("sqlcipher")
            builder.openHelperFactory(SupportOpenHelperFactory(passphrase()))
        }
        return builder.build()
    }

    /** The app's database: mode decided at first open by [encryptedAtOpen] (which may migrate), never on main when encrypted. */
    fun buildDeferred(context: Context, encryptedAtOpen: () -> Boolean, passphrase: () -> ByteArray): ContextDatabase =
        Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
            .openHelperFactory(DeferredOpenHelperFactory(encryptedAtOpen, passphrase))
            .build()

    fun inMemory(context: Context): ContextDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, ContextDatabase::class.java).allowMainThreadQueries().build()
}
