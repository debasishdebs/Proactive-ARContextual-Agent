package com.rokid.context.memory

import android.content.Context
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import java.io.File

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
                    val dbFile = app.getDatabasePath(DatabaseFactory.NAME)
                    val encrypted = when {
                        !dbFile.exists() -> encryptionRequested()
                        prefs.contains(K_ENCRYPTED) -> prefs.getBoolean(K_ENCRYPTED, false)
                        // Existing file but no flag (restored data / older code): trust the file header, not the request.
                        else -> !isPlaintextSqlite(dbFile)
                    }
                    val graph = MemoryGraph(DatabaseFactory.build(app, encrypted) { DbKeyStore.passphrase(app) })
                    prefs.edit().putBoolean(K_ENCRYPTED, encrypted).apply()
                    graph
                }.also { instance = it }
            }

        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

        /** True only for a plaintext SQLite file; anything else (including empty/short) is treated as SQLCipher. */
        internal fun isPlaintextSqlite(file: File): Boolean = runCatching {
            file.inputStream().use { ins ->
                val buf = ByteArray(SQLITE_HEADER.size)
                var n = 0
                while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
                n == buf.size && buf.contentEquals(SQLITE_HEADER)
            }
        }.getOrDefault(false)

        private val importLock = Any()

        /** Runs the legacy import once, blocking, before the first facade read/write (Review Focus #5). */
        fun ensureLegacyImported(context: Context): com.rokid.context.memory.legacy.ImportReport? {
            val graph = get(context)
            val importer = com.rokid.context.memory.legacy.LegacyImporter(context.applicationContext, graph)
            if (importer.isDone()) return null
            synchronized(importLock) {
                if (importer.isDone()) return null
                return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { importer.importAll() }
            }
        }

        fun resetForTests(db: ContextDatabase?) { synchronized(this) { instance = db?.let { MemoryGraph(it) } } }
    }
}
