package com.rokid.context.phone.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.orchestration.forget.FrameSweep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Integration carry 8: Stream F's photo sweep covers Stream C's phone captures. A "Capture now" JPEG whose frame was
 * forgotten before its row existed (tombstone, no row) is found by its frame id and deleted.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCaptureSweepTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() = MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun aForgottenPhoneCaptureJpegIsSwept() = runBlocking {
        var frame: File? = null
        val capture = PhoneCapture(ctx, enqueue = { f, _ -> frame = f }, io = Dispatchers.Unconfined, clock = { 98_765L })
        val shot = capture.target().apply { outputStream().use { Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        assertTrue(capture.ingest(shot))
        val jpg = frame!!
        assertTrue(jpg.exists())
        // The frame id is the file name without extension (RokidFrameInbox.frameIdOf).
        MemoryGraph.get(ctx).db.forgetDao().insertTombstone(ForgetTombstoneEntity(jpg.nameWithoutExtension, 1))
        assertEquals(1, FrameSweep.sweep(ctx))
        assertFalse(jpg.exists())
    }
}
