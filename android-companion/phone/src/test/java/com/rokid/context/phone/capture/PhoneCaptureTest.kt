package com.rokid.context.phone.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneCaptureTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val enqueued = mutableListOf<Pair<File, String>>()
    private val capture = PhoneCapture(ctx, enqueue = { f, s -> enqueued += f to s }, io = Dispatchers.Unconfined, clock = { 1234L })

    @Test fun ingestWritesPhoneSourceFrameAtThreeSixtyPx() = runBlocking {
        val shot = capture.target().apply { outputStream().use { Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        assertTrue(capture.ingest(shot))
        val (frame, source) = enqueued.single()
        assertEquals("rokid_phone", source)
        assertTrue(frame.name, frame.name.startsWith("rokid_phone_"))
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(frame.path, it) }
        assertTrue("width ${o.outWidth}", o.outWidth in 1..360)
        assertEquals(270, o.outHeight)
        assertFalse("the camera's full-size file is removed", shot.exists())
    }

    @Test fun aCancelledOrEmptyShotWritesNothing() = runBlocking {
        val shot = capture.target() // TakePicture cancelled: the file is empty or absent
        assertFalse(capture.ingest(shot))
        capture.discard(shot)
        assertTrue(enqueued.isEmpty())
        assertFalse(shot.exists())
    }

    @Test fun targetLivesInCacheCapture() {
        val t = capture.target()
        assertEquals(File(ctx.cacheDir, "capture"), t.parentFile)
        assertEquals("phone_1234.jpg", t.name)
    }

    // Fix round 1 (I2, I4)

    private var pausedNow = false
    private var reads = 0
    private fun control(c: PhoneCapture = capture) =
        PhoneCaptureControl(ctx, pausedNow = { reads++; pausedNow }, capture = c, io = Dispatchers.Unconfined, uri = { android.net.Uri.fromFile(it) }, scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))

    private fun photo(f: File) = f.apply { outputStream().use { Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    private fun captureDir() = File(ctx.cacheDir, "capture")

    @Test fun aPauseAtPrepareMakesNoTarget() = runBlocking {
        pausedNow = true
        assertEquals(CapturePrepare.Paused, control().prepare())
        assertTrue(captureDir().listFiles().orEmpty().isEmpty())
    }

    @Test fun aPauseThatStartsDuringTheShotDiscardsIt() = runBlocking {
        val c = control()
        val ready = c.prepare() as CapturePrepare.Ready
        photo(File(ready.uri.path!!))
        pausedNow = true
        assertEquals(CaptureResult.PAUSED, c.finish(saved = true))
        assertTrue(enqueued.isEmpty())
        assertTrue(captureDir().listFiles().orEmpty().isEmpty())
    }

    @Test fun theShotSurvivesProcessDeath() = runBlocking {
        val ready = control().prepare() as CapturePrepare.Ready
        photo(File(ready.uri.path!!))
        // A new process: a fresh control with no in-memory pending path.
        assertEquals(CaptureResult.SAVED, control(PhoneCapture(ctx, enqueue = { f, s -> enqueued += f to s }, io = Dispatchers.Unconfined, clock = { 5678L })).finish(saved = true))
        assertEquals(1, enqueued.size)
    }

    @Test fun prepareDeletesStaleCaptures() = runBlocking {
        val stale = File(captureDir().apply { mkdirs() }, "phone_1.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(control().prepare() is CapturePrepare.Ready)
        assertFalse(stale.exists())
    }

    @Test fun pausedIsReadOnIoAtRefresh() = runBlocking {
        val c = control()
        pausedNow = true
        c.refresh()
        assertEquals(true, c.paused.value)
        pausedNow = false
        c.refresh()
        assertEquals(false, c.paused.value)
        assertTrue(reads >= 2)
    }
}
