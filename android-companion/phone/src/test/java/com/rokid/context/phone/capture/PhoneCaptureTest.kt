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
}
