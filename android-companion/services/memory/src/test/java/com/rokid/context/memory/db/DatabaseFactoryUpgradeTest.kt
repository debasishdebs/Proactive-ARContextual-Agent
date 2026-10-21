package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A v1 file opened through the PRODUCTION builders (build and buildDeferred). The SQLCipher-keyed variants need the native
 * library, so they live in SqlCipherMigrationDeviceTest (encryptedDbMigratesTo2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseFactoryUpgradeTest {
    @get:Rule val helper = V1Fixture.helper()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun assertUpgraded(db: ContextDatabase) {
        val raw = db.openHelper.writableDatabase
        assertEquals(3, raw.version)
        V1Fixture.tables(1).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(raw, it)) }
        assertTrue(V1Fixture.tableNames(raw).containsAll(V1Fixture.FRAME_TABLES))
    }

    @Test fun buildUpgradesV1File() = runBlocking {
        V1Fixture.create(DatabaseFactory.NAME)
        val db = DatabaseFactory.build(ctx, encrypted = false) { error("plain") }
        try { assertUpgraded(db) } finally { db.close() }
    }

    @Test fun buildDeferredUpgradesV1File() = runBlocking(Dispatchers.IO) { // the deferred helper refuses main
        V1Fixture.create(DatabaseFactory.NAME)
        val db = DatabaseFactory.buildDeferred(ctx, { false }) { error("plain") }
        try { assertUpgraded(db) } finally { db.close() }
    }
}
