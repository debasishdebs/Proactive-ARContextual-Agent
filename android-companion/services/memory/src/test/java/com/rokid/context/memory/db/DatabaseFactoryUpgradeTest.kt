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
        assertEquals(4, raw.version)
        V1Fixture.tables(1).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(raw, it)) }
        assertTrue(V1Fixture.tableNames(raw).containsAll(V1Fixture.FRAME_TABLES))
    }

    /** A real v3 file (frame_*, canvas_* and every other table seeded) through the production builders: canvas rows survive. */
    private fun assertV3Upgraded(db: ContextDatabase) {
        val raw = db.openHelper.writableDatabase
        assertEquals(4, raw.version)
        V1Fixture.tables(3).keys.forEach { assertEquals("table $it", 1, V1Fixture.count(raw, it)) }
        assertEquals("CLOSED", raw.query("SELECT state FROM frame_cluster").use { it.moveToFirst(); it.getString(0) })
        assertTrue(raw.query("SELECT routedAt FROM frame_meta").use { it.moveToFirst(); it.isNull(0) })
    }

    private fun createV3() { helper.createDatabase(DatabaseFactory.NAME, 3).use { V1Fixture.seedVersion(it, 3) } }

    @Test fun v1FileUpgradesToV4ThroughBothBuilders() = runBlocking(Dispatchers.IO) {
        listOf(false, true).forEach { deferred ->
            ctx.getDatabasePath(DatabaseFactory.NAME).delete()
            V1Fixture.create(DatabaseFactory.NAME)
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { false }) { error("plain") } else DatabaseFactory.build(ctx, encrypted = false) { error("plain") }
            try { assertUpgraded(db) } finally { db.close() }
        }
    }

    @Test fun v3FileUpgradesToV4ThroughBothBuilders() = runBlocking(Dispatchers.IO) {
        listOf(false, true).forEach { deferred ->
            ctx.getDatabasePath(DatabaseFactory.NAME).delete()
            createV3()
            val db = if (deferred) DatabaseFactory.buildDeferred(ctx, { false }) { error("plain") } else DatabaseFactory.build(ctx, encrypted = false) { error("plain") }
            try { assertV3Upgraded(db) } finally { db.close() }
        }
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
