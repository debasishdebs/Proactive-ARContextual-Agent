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
    val scenes = SceneRepository(db)
    val proposals = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    val shopping = db.shoppingDao()
    val notes = db.noteDao()
    val spaces = EmbeddingSpaces(db.embeddingDao(), retired)
    @Volatile private var recallWired = false
    val vectorIndex: com.rokid.context.memory.retrieval.VectorIndex = com.rokid.context.memory.retrieval.BruteForceVectorIndex(db.embeddingDao())

    companion object {
        @Volatile private var instance: MemoryGraph? = null

        /**
         * Builds Room only: no file, Keystore or migration work happens here (safe on main). [encryptionRequested] is the
         * mode for a database that does not exist yet (ContextApp passes the legacy AppConfig flag); later changes go
         * through [DbEncryption.request] and apply at the next process start.
         */
        fun get(context: Context, encryptionRequested: () -> Boolean = { false }): MemoryGraph {
            com.rokid.context.memory.forget.lifecycle.ForgetV2.install(context) // Forget lifecycle M4: the flag reader, once per process; OFF unless the developer flag is set
            return getInstance(context, encryptionRequested).also { g ->
                if (!g.recallWired) { // retention v3: the Quick recall window decides which SHORT facts the backfill leaves without a vector
                    g.recallWired = true
                    val app = context.applicationContext
                    g.spaces.recallSince = { System.currentTimeMillis() - MemorySettingsStore.get(app).settings.value.recallHours * 3_600_000L }
                }
            }
        }
        private fun getInstance(context: Context, encryptionRequested: () -> Boolean): MemoryGraph =
            instance ?: synchronized(this) {
                instance ?: run {
                    // Test guard (inert on a device): a JVM unit test must never build the production (SQLCipher) database. Reaching here means the test set no
                    // MemoryGraph, or a job of an EARLIER test outlived its tearDown; fail at the caller, not later in an unrelated test with UnsatisfiedLinkError.
                    check(android.os.Build.FINGERPRINT != "robolectric") {
                        "MemoryGraph.get() would build the production SQLCipher database inside a unit test: call MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) " +
                            "first, and stop or await every job that can outlive the test"
                    }
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

        fun resetForTests(db: ContextDatabase?) {
            synchronized(this) { instance = db?.let { MemoryGraph(it) }; facadeImportFailed = false }
            testResetHooks.forEach { it() } // process singletons that captured the previous graph's database start again with this one
        }

        private val testResetHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        /**
         * A process singleton that captures [get]'s database (KeyframeGraph, the derive and moment triggers) registers its own reset here, so a test that
         * swaps the graph can never hand the next test a singleton built over an earlier (closed, or production) database. Only [resetForTests] runs them.
         */
        fun onTestReset(hook: () -> Unit) { testResetHooks += hook }
    }
}
