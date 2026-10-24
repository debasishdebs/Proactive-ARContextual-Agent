package com.rokid.context.memory.db

import android.content.Context
import androidx.room.Room
import com.rokid.context.memory.MemoryMaintenance
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    const val NAME = "context.db"

    /** Fixed mode, for instrumented tests and tools; the app uses [buildDeferred]. */
    fun build(context: Context, encrypted: Boolean, passphrase: () -> ByteArray): ContextDatabase {
        val builder = Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME).addCallback(MemoryMaintenance.openCheck).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
        if (encrypted) builder.openHelperFactory(ensureOpenParams(passphrase))
        return builder.build()
    }

    /** The app's database: mode decided at first open by [encryptedAtOpen] (which may migrate), never on main when encrypted. */
    fun buildDeferred(context: Context, encryptedAtOpen: () -> Boolean, passphrase: () -> ByteArray): ContextDatabase =
        Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
            .openHelperFactory(DeferredOpenHelperFactory(encryptedAtOpen, passphrase))
            .addCallback(MemoryMaintenance.openCheck)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .build()

    /**
     * The SQLCipher open helper factory (P8 D4). Loads the library once per process. sqlcipher-android 4.6.1's
     * `SupportOpenHelperFactory` keeps the array it is given and reuses it for every later (re)open, so it gets its own
     * copy; the copy [passphrase] returned to us is zeroed as soon as that copy is made.
     */
    internal fun ensureOpenParams(passphrase: () -> ByteArray, load: () -> Unit = { SqlCipherLoader.ensureLoaded() }): SupportOpenHelperFactory {
        load()
        val ours = passphrase()
        return try { SupportOpenHelperFactory(ours.copyOf()) } finally { ours.fill(0) }
    }

    fun inMemory(context: Context): ContextDatabase =
        Room.inMemoryDatabaseBuilder(context.applicationContext, ContextDatabase::class.java).allowMainThreadQueries().addCallback(MemoryMaintenance.openCheck).build()
}

/** Loads a native library at most once per process; a failed load is tried again next time (P8 D4). */
internal open class LibraryLoader(private val name: String) {
    @Volatile private var loaded = false

    fun ensureLoaded(load: (String) -> Unit = System::loadLibrary) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            load(name)
            loaded = true
        }
    }
}

internal object SqlCipherLoader : LibraryLoader("sqlcipher")
