package com.rokid.context.link

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceivedFrameLedgerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file get() = File(tmp.root, "ledger.txt")

    @Test fun idsSurviveARestart() {
        ReceivedFrameLedger(file).add("g1")
        assertTrue(ReceivedFrameLedger(file).contains("g1"))
        assertFalse(ReceivedFrameLedger(file).contains("g2"))
    }

    @Test fun oldestIdsAreEvictedAtCapacity() {
        val l = ReceivedFrameLedger(file, capacity = 3)
        listOf("a", "b", "c", "d").forEach(l::add)
        assertFalse(l.contains("a"))
        assertTrue(l.contains("d"))
        assertFalse(ReceivedFrameLedger(file, capacity = 3).contains("a"))
    }

    @Test fun unsafeLinesAreIgnored() {
        file.writeText("ok_1\n../x\n\n")
        val l = ReceivedFrameLedger(file)
        assertTrue(l.contains("ok_1"))
        assertFalse(l.contains("../x"))
    }

    /** A failed write must not leave the id "known": the resend would then be acked as a duplicate and lost. */
    @Test fun failedWriteForgetsTheId() {
        val dir = File(tmp.root, "ro").apply { mkdirs() }
        val l = ReceivedFrameLedger(File(dir, "ledger.txt"))
        dir.setWritable(false)
        try {
            assertFailsWith<IOException> { l.add("g1") }
            assertFalse(l.contains("g1"))
        } finally {
            dir.setWritable(true)
        }
    }

    /** Built on Main by the hub: the constructor must touch no file. */
    @Test fun constructionReadsNoFile() {
        file.writeText("early\n")
        assertTrue(file.setReadable(false))
        try {
            val l = ReceivedFrameLedger(file)
            assertTrue(file.setReadable(true))
            file.writeText("late\n")
            assertTrue(l.contains("late"), "read on first use, not in the constructor")
        } finally {
            file.setReadable(true)
        }
    }

    @Test fun anUnreadableLedgerStartsEmptyAndDoesNotThrow() {
        assertTrue(file.mkdirs())
        val logs = mutableListOf<String>()
        val l = ReceivedFrameLedger(file, log = { logs += it })
        assertFalse(l.contains("x"))
        assertFailsWith<IOException> { l.add("x") }
        assertFalse(l.contains("x"))
        val unreadable = File(tmp.root, "locked.txt").apply { writeText("a\n"); setReadable(false) }
        try {
            val l2 = ReceivedFrameLedger(unreadable, log = { logs += it })
            assertFalse(l2.contains("a"))
            assertEquals(1, logs.size)
        } finally {
            unreadable.setReadable(true)
        }
    }

    @Test fun aLeftoverTmpIsRemoved() {
        val leftover = File(tmp.root, "ledger.txt.tmp").apply { writeText("half") }
        val l = ReceivedFrameLedger(file)
        assertFalse(l.contains("half"))
        assertFalse(leftover.exists())
    }

    @Test fun aCorruptLineIsSkipped() {
        file.writeBytes("g1\n".toByteArray() + byteArrayOf(0, -1, -2, 10, 0x7f, -128) + "\ng2\n".toByteArray())
        val l = ReceivedFrameLedger(file)
        assertTrue(l.contains("g1"))
        assertTrue(l.contains("g2"))
    }

    @Test fun addFsyncsTheFileAndTheDir() {
        val files = mutableListOf<File>()
        val dirs = mutableListOf<File>()
        val l = ReceivedFrameLedger(file, fsync = { files += it }, fsyncDir = { dirs += it; true })
        l.add("g1")
        assertEquals(listOf("ledger.txt.tmp"), files.map { it.name })
        assertEquals(listOf(tmp.root.absoluteFile), dirs)
        assertTrue(ReceivedFrameLedger(file).contains("g1"))
    }
}
