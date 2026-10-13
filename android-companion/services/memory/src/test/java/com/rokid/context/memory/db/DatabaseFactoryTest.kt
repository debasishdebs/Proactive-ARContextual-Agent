package com.rokid.context.memory.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** P8 D4. The real SQLCipher path needs the native library, so it is device check D-D4; these pin our own handling. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseFactoryTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun libraryIsLoadedOnce() {
        var loads = 0
        val loader = LibraryLoader("sqlcipher")
        repeat(2) { loader.ensureLoaded { name -> assertEquals("sqlcipher", name); loads++ } }
        assertEquals(1, loads)
    }

    @Test fun aFailedLoadIsTriedAgain() {
        var loads = 0
        val loader = LibraryLoader("sqlcipher")
        runCatching { loader.ensureLoaded { loads++; throw UnsatisfiedLinkError("no lib") } }
        loader.ensureLoaded { loads++ }
        assertEquals(2, loads)
    }

    @Test fun ourPassphraseCopyIsZeroedAfterUse() {
        val ours = ByteArray(32) { (it + 1).toByte() }
        var loads = 0
        DatabaseFactory.ensureOpenParams({ ours }) { loads++ }
        assertTrue(ours.all { it == 0.toByte() })
        assertEquals(1, loads)
    }

    @Test fun theMigratorZeroesItsKeyCopyAfterTheConversion() {
        val db = File(dir.root, "context.db").apply { writeText("data") }
        val store = object : EncryptionMigrator.ModeStore {
            override var current: Boolean? = false; override var target: Boolean? = true; override var stage: Boolean? = null; override var failed = false
        }
        val seen = mutableListOf<ByteArray>()
        val handed = ByteArray(32) { 7 }
        val converter = EncryptionMigrator.DbConverter { src, dst, _, key ->
            assertArrayEquals(ByteArray(32) { 7 }, key) // intact while SQLCipher uses it
            seen += key; dst.writeText(src.readText())
        }
        EncryptionMigrator(db, store, converter) { handed }.prepare { false }
        assertTrue(seen.single().all { it == 0.toByte() })
    }
}
