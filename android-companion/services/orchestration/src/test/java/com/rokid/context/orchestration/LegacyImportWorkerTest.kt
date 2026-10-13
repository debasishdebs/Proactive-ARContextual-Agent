package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** P8 D5: a failing legacy import retries (bounded), instead of reporting success with nothing imported. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyImportWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        // A directory where the log file should be makes the import throw (the read fails with an IOException).
        File(ctx.filesDir, "memory_records.log").mkdirs()
    }
    @After fun tearDown() {
        LegacyImporter.LEGACY_FILES.forEach { File(ctx.filesDir, it).deleteRecursively() }
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun run(attempt: Int) = runBlocking { TestListenableWorkerBuilder<LegacyImportWorker>(ctx).setRunAttemptCount(attempt).build().doWork() }

    @Test fun aFailingImportRetries() {
        assertEquals(ListenableWorker.Result.retry(), run(0))
        assertEquals(ListenableWorker.Result.retry(), run(1))
    }

    @Test fun afterThreeAttemptsItFails() {
        assertEquals(3, LegacyImportWorker.MAX_RUNS)
        assertTrue(run(2) is ListenableWorker.Result.Failure)
    }
}
