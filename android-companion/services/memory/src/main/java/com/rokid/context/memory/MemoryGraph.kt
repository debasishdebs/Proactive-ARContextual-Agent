package com.rokid.context.memory

import android.content.Context
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory

/** Process-wide data graph (no DI framework in v1). The encryption mode is fixed when the DB file is first created. */
class MemoryGraph private constructor(val db: ContextDatabase) {
    val frames = FrameRepository(db.frameDao())
    val memories = MemoryRepository(db.memoryDao())
    val traces = TraceRepository(db.traceDao())

    companion object {
        private const val PREFS = "memory_graph"
        private const val K_ENCRYPTED = "db_encrypted"
        @Volatile private var instance: MemoryGraph? = null

        /** [encryptionRequested] is read only when the DB does not exist yet (ContextApp passes AppConfig.encryptionEnabled). */
        fun get(context: Context, encryptionRequested: () -> Boolean = { false }): MemoryGraph =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val exists = app.getDatabasePath(DatabaseFactory.NAME).exists()
                    val encrypted = if (exists && prefs.contains(K_ENCRYPTED)) prefs.getBoolean(K_ENCRYPTED, false) else encryptionRequested()
                    prefs.edit().putBoolean(K_ENCRYPTED, encrypted).apply()
                    MemoryGraph(DatabaseFactory.build(app, encrypted) { DbKeyStore.passphrase(app) })
                }.also { instance = it }
            }

        fun resetForTests(db: ContextDatabase?) { synchronized(this) { instance = db?.let { MemoryGraph(it) } } }
    }
}
