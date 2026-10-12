package com.rokid.context.link

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
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
}
