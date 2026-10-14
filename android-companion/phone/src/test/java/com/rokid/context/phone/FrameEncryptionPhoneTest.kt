package com.rokid.context.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameCodec
import com.rokid.context.core.ui.components.FrameDecode
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.PauseHistory
import com.rokid.context.link.ReceivedFrameLedger
import com.rokid.context.memory.frames.FrameCipher
import com.rokid.context.memory.frames.FrameImages
import com.rokid.context.memory.frames.TinkFrameCipher
import com.rokid.context.orchestration.WipeFence
import com.rokid.context.phone.capture.PhoneCapture
import com.rokid.context.phone.hub.FrameIngestor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Gaps G1 on the phone: new frames (glasses and phone camera) are sealed while encryption is on; the UI decodes both. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FrameEncryptionPhoneTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var wanted = true
    private val now = 1_793_000_000_000L
    private val jpeg: ByteArray = ByteArrayOutputStream().also { Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

    @Before fun setUp() {
        FrameReaders.install(ctx)
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { wanted }
    }
    @After fun tearDown() { FrameImages.configureForTests(null) { false }; FrameDecode.opener = FrameDecode.PlainFiles }

    private val ledger by lazy { ReceivedFrameLedger(File(tmp.root, "ledger.txt")) }
    private fun ingestor(stored: () -> Unit = {}) = FrameIngestor(
        File(tmp.root, "rokid_inbox"), FrameReassembler(),
        FrameAdmission(ledger, object : PauseHistory { override suspend fun pausedAt(phoneMs: Long) = false }),
        ledger, wipeFence = { WipeFence.Fence.None }, wipeLock = Mutex(),
    ) { stored() }

    @Test fun encryptionOnEncryptsNewFrames() = runBlocking<Unit> {
        // Glasses frame: sealed in the inbox, acked as usual.
        val r = ingestor().onChunk(FrameCodec.split("g1", jpeg, capturedAtMs = now - 60_000 + 140).single(), ClockOffset(140), now)
        assertEquals(FrameAck(frameId = "g1", ok = true), r.ack)
        val f = File(tmp.root, "rokid_inbox/rokid_${now - 60_000}_g1.jpg")
        assertTrue(FrameImages.isSealed(f))
        assertArrayEquals(jpeg, FrameImages.readBytes(f.path))
        // Phone camera capture: sealed in the archive.
        val enqueued = mutableListOf<File>()
        val capture = PhoneCapture(ctx, enqueue = { file, _ -> enqueued += file }, io = Dispatchers.Unconfined, clock = { 42L })
        val shot = capture.target().apply { writeBytes(jpeg) }
        assertTrue(capture.ingest(shot))
        assertTrue(FrameImages.isSealed(enqueued.single()))
        assertNotNull(FrameDecode.decode(enqueued.single().path, 32))
        enqueued.single().delete()
    }

    @Test fun aSealFailureStoresNothingAndSendsNoAck() = runBlocking {
        FrameImages.configureForTests(object : FrameCipher {
            override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("keystore")
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException("keystore")
        }) { true }
        var stored = 0
        val r = ingestor { stored++ }.onChunk(FrameCodec.split("g2", jpeg, capturedAtMs = now - 60_000 + 140).single(), ClockOffset(140), now)
        assertNull("the glasses keep the frame and resend it", r.ack)
        assertEquals(0, stored)
        assertTrue(File(tmp.root, "rokid_inbox").listFiles().orEmpty().isEmpty())
    }

    @Test fun readersOpenBothPlainAndEncrypted() {
        val plain = File(tmp.root, "plain.jpg").apply { writeBytes(jpeg) }
        val sealed = File(tmp.root, "sealed.jpg").apply { writeBytes(FrameImages.forDisk(jpeg)) }
        assertFalse(BitmapFactory.decodeFile(sealed.path) != null)
        // Thumbnails (Activity, Ask/Nudge sources), Memory detail and the Frames viewer all decode through FrameDecode.
        for (f in listOf(plain, sealed)) {
            val bmp = FrameDecode.decode(f.path, 32)
            assertNotNull(f.name, bmp)
            assertEquals(64, bmp!!.width); assertEquals(48, bmp.height)
        }
        assertNull(FrameDecode.decode(File(tmp.root, "gone.jpg").path, 32))
    }
}
