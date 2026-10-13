package com.rokid.context.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Task 20 fix round 3: Delete all marks the legacy import done under the import lock, without importing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LegacyImportDoneTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() = MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    /** An import in progress holds the lock: marking done waits for it to finish (or abort), then wins. */
    @Test fun markingDoneWaitsForAnImportInProgress() {
        val marked = CountDownLatch(1)
        val importing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val importer = Thread { synchronized(MemoryGraph.importLock) { importing.countDown(); release.await() } } // stands in for importAll()
        importer.start(); importing.await()
        val wipe = Thread { MemoryGraph.markLegacyImportDone(ctx); marked.countDown() }.apply { start() }
        assertFalse("must wait for the import", marked.await(300, TimeUnit.MILLISECONDS))
        assertFalse(LegacyImporter(ctx, MemoryGraph.get(ctx)).isDone())
        release.countDown()
        assertTrue(marked.await(5, TimeUnit.SECONDS)); wipe.join(); importer.join()
        assertTrue(LegacyImporter(ctx, MemoryGraph.get(ctx)).isDone())
    }
}
