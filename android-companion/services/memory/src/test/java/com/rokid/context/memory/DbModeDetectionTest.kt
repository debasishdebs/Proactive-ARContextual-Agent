package com.rokid.context.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DbModeDetectionTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun plaintextHeaderIsDetected() {
        val f = tmp.newFile("plain.db")
        f.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(100))
        assertTrue(MemoryGraph.isPlaintextSqlite(f))
    }

    @Test fun randomBytesAreTreatedAsEncrypted() {
        val f = tmp.newFile("enc.db")
        f.writeBytes(ByteArray(4096) { (it * 31 + 7).toByte() })
        assertFalse(MemoryGraph.isPlaintextSqlite(f))
    }

    @Test fun emptyOrShortFileIsNotPlaintext() {
        assertFalse(MemoryGraph.isPlaintextSqlite(tmp.newFile("empty.db")))
        val short = tmp.newFile("short.db").apply { writeBytes("SQLite".toByteArray()) }
        assertFalse(MemoryGraph.isPlaintextSqlite(short))
    }
}
