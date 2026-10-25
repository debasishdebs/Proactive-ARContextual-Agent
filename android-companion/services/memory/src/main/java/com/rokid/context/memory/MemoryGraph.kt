package com.rokid.context.memory

import com.rokid.context.core.log.PcLog
import android.content.Context
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import java.io.File

/** Process-wide data graph (no DI framework in v1). The encryption mode is decided at first open (EncryptionMigrator). */
class MemoryGraph private constructor(val db: ContextDatabase, retired: RetiredClock = InMemoryRetiredClock()) {
    val frames = FrameRepository(db.frameDao())
    val intake = FrameIntake(db)
    val memories = MemoryRepository(db.memoryDao())
    val traces = TraceRepository(db.traceDao())
    val timeline = TimelineRepository(db.frameViewDao(), db.memoryViewDao(), db.traceViewDao())
    val memoryEdits = MemoryEdits(db.memoryViewDao(), memories)
    val asks = AskRepository(db.askDao())
    val nudges = NudgeRepository(db.nudgeDao())
    val marks = MarkRepository(db.momentDao())
    val proposals = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    val shopping = db.shoppingDao()
    val notes = db.noteDao()
    val spaces = EmbeddingSpaces(db.embeddingDao(), retired)
    val vectorIndex: com.rokid.context.memory.retrieval.VectorIndex = com.rokid.context.memory.retrieval.BruteForceVectorIndex(db.embeddingDao())

    companion object {
        @Volatile private var instance: MemoryGraph? = null

        /**
         * Builds Room only: no file, Keystore or migration work happens here (safe on main). [encryptionRequested] is the
         * mode for a database that does not exist yet (ContextApp passes the legacy AppConfig flag); later changes go
         * through [DbEncryption.request] and apply at the next process start.
         */
        fun get(context: Context, encryptionRequested: () -> Boolean = { false }): MemoryGraph =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val migrator = com.rokid.context.memory.db.EncryptionMigrator(
                        app.getDatabasePath(DatabaseFactory.NAME), com.rokid.context.memory.db.PrefsModeStore(app),
                        com.rokid.context.memory.db.SqlCipherConverter,
                    ) { DbKeyStore.passphrase(app) }
                    MemoryGraph(DatabaseFactory.buildDeferred(app, { migrator.prepare(encryptionRequested) }) { DbKeyStore.passphrase(app) }, PrefsRetiredClock(app))
                }.also { instance = it }
            }

        internal fun isPlaintextSqlite(file: File): Boolean = com.rokid.context.memory.db.EncryptionMigrator.isPlaintextSqlite(file)

        /** Held by a running legacy import (until it finishes or throws) and by [markLegacyImportDone]. */
        internal val importLock = Any()

        /**
         * Delete all (fix round 3): never imports. Waits for an import in progress to finish or abort, then marks the
         * import done so it never runs; the wipe deletes the legacy logs, so nothing the user didn't delete is lost.
         */
        fun markLegacyImportDone(context: Context) {
            synchronized(importLock) { com.rokid.context.memory.legacy.LegacyImporter.markDone(context.applicationContext) }
        }

        /** Set by a failed facade import: the facades try at most once per process; the worker keeps retrying. */
        @Volatile private var facadeImportFailed = false

        /**
         * Runs the legacy import once, blocking, before the first facade read/write (Review Focus #5). P8 D5:
         * - on Main it returns null at once, without taking [importLock]: the worker (or a background caller) imports;
         * - a failed import is logged (class only) and returns null, never throwing; it is not marked done, so the
         *   worker or the next process retries, and the facades make no second attempt in this process.
         */
        fun ensureLegacyImported(
            context: Context,
            isMain: () -> Boolean = { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() },
        ): com.rokid.context.memory.legacy.ImportReport? = ensureLegacyImported(context, isMain, null)

        internal fun ensureLegacyImported(
            context: Context,
            isMain: () -> Boolean,
            import: (suspend () -> com.rokid.context.memory.legacy.ImportReport)?,
        ): com.rokid.context.memory.legacy.ImportReport? {
            if (isMain() || facadeImportFailed) return null
            return try {
                importOnce(context, import)
            } catch (e: Exception) {
                facadeImportFailed = true
                PcLog.w("MemoryGraph", "legacy import failed; the worker retries: ${e.javaClass.simpleName}")
                null
            }
        }

        /** LegacyImportWorker only: the same import, but a failure throws so the worker can retry. Never on Main. */
        fun importLegacyForWorker(context: Context): com.rokid.context.memory.legacy.ImportReport? = importOnce(context, null)

        private fun importOnce(context: Context, import: (suspend () -> com.rokid.context.memory.legacy.ImportReport)?): com.rokid.context.memory.legacy.ImportReport? {
            val graph = get(context)
            val importer = com.rokid.context.memory.legacy.LegacyImporter(context.applicationContext, graph)
            if (importer.isDone()) return null
            synchronized(importLock) {
                if (importer.isDone()) return null
                return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { (import ?: importer::importAll)() }
            }
        }

        fun resetForTests(db: ContextDatabase?) { synchronized(this) { instance = db?.let { MemoryGraph(it) }; facadeImportFailed = false } }
    }
}
