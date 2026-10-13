package com.rokid.context.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.ImportReport
import com.rokid.context.memory.legacy.LegacyImporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** P8 D5: facade calls never import on Main, never throw for a failed import, and try at most once per process. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryGraphImportTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_1\tshort_term\t0.9\t100\tMilk carton nearly empty\n")
    }
    @After fun tearDown() {
        LegacyImporter.LEGACY_FILES.forEach { File(ctx.filesDir, it).delete() }
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    @Test fun ensureLegacyImportedOnMainDoesNotImport() {
        var calls = 0
        assertNull(MemoryGraph.ensureLegacyImported(ctx, isMain = { true }) { calls++; ImportReport(0, 0, 0, 0) })
        assertEquals(0, calls)
        assertFalse(LegacyImporter(ctx, MemoryGraph.get(ctx)).isDone())
    }

    @Test fun ensureLegacyImportedOnMainDoesNotWaitForTheImportLock() {
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val t = Thread { synchronized(MemoryGraph.importLock) { held.countDown(); release.await() } }.apply { start() }
        held.await()
        try {
            assertNull(MemoryGraph.ensureLegacyImported(ctx, isMain = { true })) // returns at once: the lock is never taken
        } finally { release.countDown(); t.join() }
    }

    @Test fun aFailingImportDoesNotThrowToCallers() {
        var calls = 0
        val failing: suspend () -> ImportReport = { calls++; throw IOException("disk") }
        assertNull(MemoryGraph.ensureLegacyImported(ctx, isMain = { false }, import = failing))
        assertNull(MemoryGraph.ensureLegacyImported(ctx, isMain = { false }, import = failing)) // at most one facade attempt
        assertEquals(1, calls)
        assertFalse(LegacyImporter(ctx, MemoryGraph.get(ctx)).isDone())
        // The worker path still retries, and succeeds.
        assertEquals(1, MemoryGraph.importLegacyForWorker(ctx)?.memories)
        assertTrue(LegacyImporter(ctx, MemoryGraph.get(ctx)).isDone())
    }
}
