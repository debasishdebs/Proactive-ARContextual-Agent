package com.rokid.context.memory.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** Robolectric only because the failure path logs through android.util.Log (preflight F11). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EncryptionMigratorTest {
    @get:Rule val dir = TemporaryFolder()
    private val db by lazy { File(dir.root, "context.db") }
    private val tmp by lazy { File(dir.root, "context.db.migrating") }
    private val old by lazy { File(dir.root, "context.db.old") }
    private val store = object : EncryptionMigrator.ModeStore {
        override var current: Boolean? = null; override var target: Boolean? = null; override var stage: Boolean? = null; override var failed = false
    }
    private var fail = false
    private val converter = EncryptionMigrator.DbConverter { src, dst, toEncrypted, _ ->
        if (fail) throw IOException("disk full")
        dst.writeText((if (toEncrypted) "ENC:" else "PLAIN:") + src.readText())
    }
    private val migrator by lazy { EncryptionMigrator(db, store, converter) { ByteArray(32) } }

    @Test fun aNewDatabaseUsesTheTargetElseTheRequest() {
        store.target = true
        assertTrue(migrator.prepare { false }); assertEquals(true, store.current); assertNull(store.target)
        store.current = null
        assertFalse(migrator.prepare { false }); assertEquals(false, store.current)
    }

    @Test fun aPendingTargetConvertsAndSwaps() {
        db.writeText("data"); store.current = false; store.target = true
        File(db.path + "-wal").writeText("old wal")
        assertTrue(migrator.prepare { false })
        assertEquals("ENC:data", db.readText()); assertEquals(true, store.current)
        assertNull(store.target); assertNull(store.stage)
        assertFalse(tmp.exists()); assertFalse(old.exists()); assertFalse(File(db.path + "-wal").exists())
    }

    @Test fun failedConversionKeepsTheOldDatabase() {
        db.writeText("data"); store.current = false; store.target = true; fail = true
        assertFalse(migrator.prepare { false })
        assertEquals("data", db.readText()); assertTrue(store.failed); assertNull(store.target); assertFalse(tmp.exists())
    }

    @Test fun crashAfterExportBeforeSwapIsFinished() {
        db.writeText("data"); tmp.writeText("ENC:data"); store.current = false; store.stage = true
        assertTrue(migrator.prepare { false })
        assertEquals("ENC:data", db.readText()); assertFalse(tmp.exists()); assertFalse(old.exists())
    }

    @Test fun crashBetweenRenamesIsFinished() {
        old.writeText("data"); tmp.writeText("ENC:data"); File(db.path + "-journal").writeText("old journal")
        store.current = false; store.stage = true
        assertTrue(migrator.prepare { false })
        assertEquals("ENC:data", db.readText()); assertFalse(old.exists()); assertFalse(File(db.path + "-journal").exists())
    }

    @Test fun crashAfterTheRenamesOnlyFixesTheFlags() {
        db.writeText("ENC:data"); old.writeText("data"); store.current = false; store.stage = true
        assertTrue(migrator.prepare { false })
        assertEquals("ENC:data", db.readText()); assertEquals(true, store.current); assertFalse(old.exists())
    }

    @Test fun aStrayTmpWithoutAStageIsDiscardedAndTheSameTargetIsANoOp() {
        db.writeText("data"); tmp.writeText("half"); store.current = false; store.target = false
        assertFalse(migrator.prepare { true })
        assertFalse(tmp.exists()); assertEquals("data", db.readText()); assertNull(store.target)
    }

    @Test fun withoutAFlagTheHeaderDecides() {
        db.writeBytes("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(100))
        assertFalse(migrator.prepare { true }); assertEquals(false, store.current)
    }

    @Test fun crashAfterTheFirstRenameWithNoConvertedFileRestoresTheOldDatabase() {
        old.writeText("data"); store.current = false; store.stage = true
        assertFalse(migrator.prepare { true })
        assertEquals("data", db.readText()); assertFalse(old.exists()); assertEquals(false, store.current); assertNull(store.stage)
    }

    @Test fun theKeyIsReadOnlyWhenAConversionRuns() {
        var reads = 0
        val m = EncryptionMigrator(db, store, converter) { reads++; ByteArray(32) }
        db.writeText("data"); store.current = false
        assertFalse(m.prepare { true }); assertEquals(0, reads)
        store.target = true
        assertTrue(m.prepare { false }); assertEquals(1, reads)
    }
}
