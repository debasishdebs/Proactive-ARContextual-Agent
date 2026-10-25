package com.rokid.context.memory.frames

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

/** Gaps G1: frame JPEGs at rest follow "Encrypt memory"; every reader opens plain and sealed files alike. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FrameImagesTest {
    @get:org.junit.Rule val pcLogShadow = com.rokid.context.memory.PcLogToShadowRule()
    @get:Rule val tmp = TemporaryFolder()
    private var wanted = false
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(2_000) { (it % 251).toByte() }

    @Before fun setUp() { FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { wanted } }
    @After fun tearDown() = FrameImages.configureForTests(null) { false }

    private fun plainFile(name: String = "rokid_1_a.jpg") = File(tmp.root, name).apply { writeBytes(jpeg); setLastModified(1_700_000_000_000L) }

    @Test fun encryptionOnEncryptsNewFrames() {
        wanted = true
        val onDisk = FrameImages.forDisk(jpeg)
        assertTrue(FrameImages.isSealed(onDisk))
        assertFalse("no JPEG magic at rest", onDisk[0] == 0xFF.toByte() && onDisk[1] == 0xD8.toByte())
        val f = File(tmp.root, "new.jpg").apply { writeBytes(onDisk) }
        assertArrayEquals(jpeg, FrameImages.readBytes(f.path))
        wanted = false
        assertArrayEquals("off: written as is", jpeg, FrameImages.forDisk(jpeg))
    }

    @Test fun readersOpenBothPlainAndEncrypted() {
        val plain = plainFile("p.jpg")
        wanted = true
        val sealed = File(tmp.root, "s.jpg").apply { writeBytes(FrameImages.forDisk(jpeg)) }
        assertArrayEquals(jpeg, FrameImages.open(plain.path)!!.use { it.readBytes() })
        assertArrayEquals(jpeg, FrameImages.open(sealed.path)!!.use { it.readBytes() })
        assertNull(FrameImages.open(null)); assertNull(FrameImages.open("")); assertNull(FrameImages.open(File(tmp.root, "gone.jpg").path))
        assertNull("empty file", FrameImages.open(File(tmp.root, "empty.jpg").apply { writeBytes(ByteArray(0)) }.path))
        // A sealed file whose key can't open it (another key, tampered): null, never a throw, never garbage.
        FrameImages.configureForTests(TinkFrameCipher.inMemoryForTests()) { true }
        assertNull(FrameImages.open(sealed.path))
        assertArrayEquals("plain files never need the key", jpeg, FrameImages.readBytes(plain.path))
    }

    @Test fun convertEncryptsAndDecryptsInPlaceKeepingNameAndTime() = runBlocking {
        val f = plainFile()
        assertEquals(FrameImages.Convert.DONE, FrameImages.convert(f, toSealed = true))
        assertTrue(FrameImages.isSealed(f))
        assertEquals(1_700_000_000_000L, f.lastModified())
        assertEquals(FrameImages.Convert.SKIPPED, FrameImages.convert(f, toSealed = true))
        assertEquals(FrameImages.Convert.DONE, FrameImages.convert(f, toSealed = false))
        assertArrayEquals(jpeg, f.readBytes())
        assertEquals(1_700_000_000_000L, f.lastModified())
        assertTrue("no temp left", tmp.root.listFiles()!!.all { it == f })
    }

    @Test fun migrationInterruptedNeverLosesAFile() = runBlocking {
        val f = plainFile()
        // Dies after the sealed copy is written and fsynced, before it replaces the plain one.
        try {
            FrameImages.convert(f, toSealed = true, beforeCommit = { throw IOException("process died") })
        } catch (e: IOException) { /* the "crash": its sealed copy stays behind, as after a real process death */ }
        assertArrayEquals("the plain file is intact", jpeg, f.readBytes())
        // A temp left by a real crash (no cleanup) is swept, and the next pass finishes the job.
        File(tmp.root, f.name + FrameImages.TEMP_SUFFIX).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(1, FrameImages.sweepTemps(tmp.root))
        assertEquals(FrameImages.Convert.DONE, FrameImages.convert(f, toSealed = true))
        assertArrayEquals(jpeg, FrameImages.readBytes(f.path))
        // A commit refused (file deleted or moved meanwhile) drops the copy and never recreates the file.
        val g = plainFile("g.jpg")
        assertEquals(FrameImages.Convert.SKIPPED, FrameImages.convert(g, toSealed = true, commit = { g.delete(); false }))
        assertFalse(g.exists())
        assertEquals(listOf(f.name), tmp.root.list()!!.toList())
    }

    @Test fun aCipherFailureKeepsTheFileAndLogsOnlyACode() = runBlocking {
        val f = plainFile("rokid_99_private.jpg")
        FrameImages.configureForTests(object : FrameCipher {
            override fun seal(plain: ByteArray): ByteArray = throw IllegalStateException("keystore " + f.absolutePath)
            override fun open(sealed: ByteArray): ByteArray = throw IllegalStateException(f.absolutePath)
        }) { true }
        ShadowLog.clear()
        assertEquals(FrameImages.Convert.FAILED, FrameImages.convert(f, toSealed = true))
        assertArrayEquals(jpeg, f.readBytes())
        assertEquals(listOf(f.name), tmp.root.list()!!.toList())
        val log = ShadowLog.getLogsForTag("FrameImages").joinToString("\n") { it.msg }
        assertTrue(log, log.contains("code=SEAL"))
        assertFalse(log, log.contains("private") || log.contains(tmp.root.path))
        try { FrameImages.forDisk(jpeg); throw AssertionError("must not write plaintext while encryption is on") } catch (e: IOException) { assertNotNull(e) }
    }
}
