package com.rokid.context.orchestration

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Gaps G1: the toggle migrates frame JPEGs both ways, resumably; vision input and Delete all handle sealed files. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameEncryptionTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var wanted = false
    private fun jpeg(seed: Int) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(1_000) { ((it + seed) % 251).toByte() }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
        RokidFrameInbox.FRAME_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { wanted }
    }

    @After fun tearDown() {
        FrameImages.configureForTests(null) { false }
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true)
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    /** Three frames: one waiting in the inbox, two archived (one a phone capture). */
    private fun frames(): Map<File, ByteArray> = listOf(
        File(RokidFrameInbox.inboxDir(ctx), "rokid_1_a.jpg"),
        File(RokidFrameInbox.archiveDir(ctx), "10_rokid_2_b.jpg"),
        File(RokidFrameInbox.archiveDir(ctx), "rokid_phone_3.jpg"),
    ).mapIndexed { i, f -> f.parentFile!!.mkdirs(); val b = jpeg(i); f.writeBytes(b); f to b }.toMap()

    private fun frameFiles() = RokidFrameInbox.FRAME_DIRS.flatMap { File(ctx.filesDir, it).listFiles().orEmpty().toList() }.sortedBy { it.name }

    @Test fun toggleOnMigratesExistingFramesResumably() = runBlocking {
        val files = frames()
        wanted = true
        var converted = 0
        // Stopped by WorkManager after one file: the run says "more to do" and the rest are untouched.
        assertEquals(FrameCryptoMigration.Outcome.STOPPED, FrameCryptoMigration.run(ctx, isStopped = { converted >= 1 }, onConverted = { converted++ }))
        assertEquals(1, files.keys.count { FrameImages.isSealed(it) })
        // The next run finishes, and skips what is done.
        assertEquals(FrameCryptoMigration.Outcome.DONE, FrameCryptoMigration.run(ctx, onConverted = { converted++ }))
        assertEquals(3, converted)
        files.forEach { (f, b) -> assertTrue(f.name, FrameImages.isSealed(f)); assertArrayEquals(b, FrameImages.readBytes(f.path)) }
        assertEquals(files.keys.map { it.name }.sorted(), frameFiles().map { it.name })
    }

    @Test fun toggleOffDecryptsBack() = runBlocking {
        val files = frames()
        wanted = true
        assertEquals(FrameCryptoMigration.Outcome.DONE, FrameCryptoMigration.run(ctx))
        wanted = false
        assertEquals(FrameCryptoMigration.Outcome.DONE, FrameCryptoMigration.run(ctx))
        files.forEach { (f, b) -> assertFalse(FrameImages.isSealed(f)); assertArrayEquals(b, f.readBytes()) }
    }

    @Test fun migrationInterruptedNeverLosesAFile() = runBlocking {
        val files = frames()
        wanted = true
        var n = 0
        // The process dies on the second file, its sealed copy written and fsynced but not yet swapped in.
        try {
            FrameCryptoMigration.run(ctx, beforeCommit = { if (++n == 2) throw IOException("process died") })
            throw AssertionError("expected the crash")
        } catch (e: IOException) { /* crashed */ }
        files.forEach { (f, b) -> assertTrue(f.exists()); assertArrayEquals(b, FrameImages.readBytes(f.path)) }
        // Off, then on again: every pass converts toward the current choice and sweeps the leftover copy first.
        wanted = false
        assertEquals(FrameCryptoMigration.Outcome.DONE, FrameCryptoMigration.run(ctx))
        wanted = true
        assertEquals(FrameCryptoMigration.Outcome.DONE, FrameCryptoMigration.run(ctx))
        files.forEach { (f, b) -> assertTrue(FrameImages.isSealed(f)); assertArrayEquals(b, FrameImages.readBytes(f.path)) }
        assertEquals("the leftover copy is swept, nothing else added or lost", files.keys.map { it.name }.sorted(), frameFiles().map { it.name })
    }

    @Test fun aWipeDuringTheMigrationIsNeverUndone() = runBlocking {
        val files = frames()
        wanted = true
        FrameCryptoMigration.run(ctx, beforeCommit = { files.keys.forEach { it.delete() } })
        assertTrue("nothing recreated", frameFiles().isEmpty())
    }

    @Test fun enqueueIsUnique() {
        FrameCryptoWorker.enqueue(ctx); FrameCryptoWorker.enqueue(ctx)
        assertEquals(1, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(FrameCryptoWorker.NAME).get().size)
    }

    @Test fun visionInputReadsEncryptedFrames() = runBlocking {
        wanted = true
        val plain = jpeg(7)
        val f = File(RokidFrameInbox.archiveDir(ctx).apply { mkdirs() }, "rokid_9_v.jpg").apply { writeBytes(FrameImages.forDisk(plain)) }
        assertTrue(FrameImages.isSealed(f))
        val inputs = FrameInputs.fromFrames(listOf(FrameEntity("rokid_9_v", 1, f.absolutePath, "rokid_glasses")), maxImages = 1)
        assertEquals(1, inputs.size)
        assertEquals("image/jpeg", inputs.single().mimeType)
        assertArrayEquals(plain, Base64.decode(inputs.single().base64Data, Base64.NO_WRAP))
    }

    @Test fun deleteAllRemovesEncryptedFrames() = runBlocking {
        val files = frames()
        wanted = true
        FrameCryptoMigration.run(ctx)
        File(RokidFrameInbox.archiveDir(ctx), "rokid_4_c.jpg" + FrameImages.TEMP_SUFFIX).writeBytes(byteArrayOf(1))
        val archived = files.keys.first { it.name == "10_rokid_2_b.jpg" }
        MemoryGraph.get(ctx).frames.add(FrameEntity("10_rokid_2_b", 1, archived.absolutePath, "rokid_glasses"))
        assertTrue(MemoryWipe.deleteAll(ctx) >= 4)
        assertTrue(frameFiles().isEmpty())
    }
}
