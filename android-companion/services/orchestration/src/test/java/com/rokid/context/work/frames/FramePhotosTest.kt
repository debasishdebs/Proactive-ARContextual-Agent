package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.frames.FrameCipher
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Review I3: only a frame that can never be read again is final; a file the key can't open right now is a normal failure. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FramePhotosTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val good = TinkFrameCipher.inMemoryForTests()
    private var keystoreDown = false
    private val flaky = object : FrameCipher {
        override fun seal(plain: ByteArray) = good.seal(plain)
        override fun open(sealed: ByteArray): ByteArray = if (keystoreDown) throw IllegalStateException("Keystore unavailable") else good.open(sealed)
    }

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        FrameImages.configureForTests(flaky) { true }
    }

    @After fun tearDown() {
        FrameImages.configureForTests(null) { false }
        db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun frame(id: String, sealed: Boolean = true): File {
        val f = File(ctx.cacheDir, "$id.jpg")
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        f.writeBytes(if (sealed) FrameImages.forDisk(plain) else plain)
        db.frameDao().insertIgnore(FrameEntity(id, 1000, f.absolutePath, "rokid_glasses"))
        return f
    }

    @Test fun readableFramesGoOut() = runBlocking {
        frame("a"); frame("b", sealed = false)
        val r = FramePhotos.load(ctx, listOf("a", "b"))
        assertEquals(listOf("a", "b"), r.photos.map { it.frameId }); assertEquals(emptySet<String>(), r.gone + r.unseen)
    }

    @Test fun aKeystoreHiccupIsUnseenNotGone() = runBlocking {
        frame("a")
        keystoreDown = true
        val r = FramePhotos.load(ctx, listOf("a"))
        assertEquals(setOf("a"), r.unseen); assertEquals(emptySet<String>(), r.gone); assertEquals(emptyList<String>(), r.photos.map { it.frameId })
        keystoreDown = false
        assertEquals("readable again once the key opens", listOf("a"), FramePhotos.load(ctx, listOf("a")).photos.map { it.frameId })
    }

    @Test fun missingRowMissingFileAndForgottenFramesAreGone() = runBlocking {
        frame("file").delete()
        frame("forgotten")
        db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone(frameId, createdAt) VALUES ('forgotten', 1)")
        frame("ok")
        val r = FramePhotos.load(ctx, listOf("file", "forgotten", "norow", "ok"))
        assertEquals(setOf("file", "forgotten", "norow"), r.gone); assertEquals(emptySet<String>(), r.unseen)
        assertEquals(listOf("ok"), r.photos.map { it.frameId })
    }
}
