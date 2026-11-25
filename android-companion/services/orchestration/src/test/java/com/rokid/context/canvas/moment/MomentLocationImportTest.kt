package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.core.model.zone.PauseZone
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.MomentLocationEntity
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.db.PauseIntervalEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentLocationImportTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val dao = db.momentLocationDao()
    private val builder = MomentBuilder(db)
    private var enabled = true
    private var zones: List<PauseZone>? = emptyList()
    @After fun close() = db.close()

    private val capture = MS + 5_000
    private val id = MomentId.format(SEC + 5)

    private fun imp() = MomentLocationImport(db, { 42L }, { enabled }, { zones }, { 0L })
    private fun row(momentId: String = id, anchor: String = "f1", kind: String = "FRAME", lat: Double = 12.0, lon: Double = 77.0, at: Long = capture) =
        MomentLocationImport.Row(momentId, at, kind, anchor, lat, lon, 50.0, 30_000, "UNKNOWN", "NEAREST")
    private suspend fun seed() { fx.frame("f1", capture); builder.build(listOf(id)) }
    private fun one(r: MomentLocationImport.Row = row()) = runBlocking { imp().run(listOf(r)) }

    @Test fun writesBackfillRowWithItsClassAndMethod() = runBlocking {
        seed()
        val rep = imp().run(listOf(row()))
        assertEquals(1, rep.written); assertEquals(1, rep.received); assertEquals(emptyMap<String, Int>(), rep.skipped)
        val e = dao.of(listOf(id)).single()
        assertEquals("MEDIA_BACKFILL", e.source); assertEquals("UNKNOWN", e.speedClass); assertEquals("NEAREST", e.method); assertEquals(30_000L, e.fixAgeMs); assertEquals(42L, e.savedAt)
    }

    @Test fun idempotentSecondRunWritesZero() = runBlocking {
        seed()
        assertEquals(1, imp().run(listOf(row())).written)
        val second = imp().run(listOf(row()))
        assertEquals(0, second.written); assertEquals(1, second.skipped["present"]); assertEquals(1, dao.count())
    }

    @Test fun neverOverwritesLiveRow() = runBlocking {
        seed()
        dao.insertIfAbsent(MomentLocationEntity(id, capture, "FRAME", "f1", 1.0, 2.0, 10.0, 100, "ZONE_STREAM", null, null, 7))
        val rep = imp().run(listOf(row(lat = 50.0, lon = 60.0)))
        assertEquals(0, rep.written); assertEquals(1, rep.skipped["present"])
        val e = dao.of(listOf(id)).single(); assertEquals(1.0, e.lat, 0.0); assertEquals("ZONE_STREAM", e.source)
    }

    @Test fun zoneBecomingActiveBetweenChunksRefusesTheLaterChunk() = runBlocking {
        seed()
        var calls = 0
        val imp = MomentLocationImport(db, { 42L }, { enabled }, { if (calls++ == 0) emptyList() else listOf(PauseZone("z", "Home", 11.99, 76.99, 12.01, 77.01)) }, { 0L })
        val rows = (1..MomentLocationImport.CHUNK + 1).map { row() }
        val rep = imp.run(rows)
        assertEquals(1, rep.written); assertEquals(MomentLocationImport.CHUNK - 1 + 1, rep.skipped.values.sum()); assertEquals(1, rep.skipped["zone"]); assertEquals(MomentLocationImport.CHUNK - 1, rep.skipped["present"])
    }

    @Test fun goneTombstonedZonePausedRefused() = runBlocking {
        // no moment at all
        assertEquals(1, one().skipped["no_moment"])
        seed()
        // the anchor does not match a part of the Moment
        assertEquals(1, one(row(anchor = "other")).skipped["no_anchor"]); assertEquals(1, one(row(kind = "MARK")).skipped["no_anchor"])
        // paused second
        db.pauseDao().insertInterval(PauseIntervalEntity("manual:1", "PHONE", capture - 1000, null))
        assertEquals(1, one().skipped["paused"])
        db.pauseDao().closeInterval("manual:1", capture - 500)
        // inside a pause zone, and unreadable zones
        zones = listOf(PauseZone("z", "Home", 11.99, 76.99, 12.01, 77.01))
        assertEquals(1, one().skipped["zone"])
        zones = null; assertEquals(1, one().skipped["zone"])
        zones = emptyList()
        // a forgotten photo and a forgotten Moment
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1))
        assertEquals(1, one().skipped["tombstoned"])
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id, 1)))
        assertEquals(1, one().skipped["tombstoned"])
        enabled = false; assertEquals(1, one().skipped["setting_off"])
        assertEquals(0, dao.count()); assertNull(dao.of(listOf(id)).firstOrNull())
    }

    @Test fun badRowCountedNotFatal() = runBlocking {
        seed()
        val good = MomentLocationImport.parseRow("$id\t$capture\tFRAME\tf1\t12.0\t77.0\t50.0\t30000\tWALKING\tINTERPOLATED")
        assertNotNull(good)
        val bads = listOf(
            "zz\t$capture\tFRAME\tf1\t12.0\t77.0\t50.0\t30000\tWALKING\tNEAREST",                // id
            "$id\t$capture\tFRAME\tf1\t91.0\t77.0\t50.0\t30000\tWALKING\tNEAREST",              // lat
            "$id\t$capture\tFRAME\tf1\tNaN\t77.0\t50.0\t30000\tWALKING\tNEAREST",
            "$id\t$capture\tFRAME\tf1\t12.0\t77.0\t0\t30000\tWALKING\tNEAREST",                 // accuracy
            "$id\t$capture\tFRAME\tf1\t12.0\t77.0\t5000.5\t30000\tWALKING\tNEAREST",
            "$id\t$capture\tFRAME\tf1\t12.0\t77.0\t50.0\t3600001\tWALKING\tNEAREST",            // fixAge
            "$id\t${capture + 60_001}\tFRAME\tf1\t12.0\t77.0\t50.0\t1\tWALKING\tNEAREST",       // atMs far from id second
            "$id\t$capture\tPHOTO\tf1\t12.0\t77.0\t50.0\t1\tWALKING\tNEAREST",                  // kind
            "$id\t$capture\tFRAME\tf1\t12.0\t77.0\t50.0\t1\tRUNNING\tNEAREST",                  // class
            "$id\t$capture\tFRAME\tf1\t12.0\t77.0\t50.0\t1\tWALKING\tGUESS",                    // method
            "$id\t$capture\tFRAME\tf1\t12.0\t77.0",                                             // short
        )
        bads.forEach { assertNull(it, MomentLocationImport.parseRow(it)) }
        val rep = imp().run(listOf(good!!), badRows = bads.size)
        assertEquals(1, rep.written); assertEquals(bads.size, rep.skipped["bad_row"]); assertEquals(bads.size + 1, rep.received)
    }

    @Test fun chunksOf200AllWritten() = runBlocking {
        val ids = (0 until 450).map { MomentId.format(SEC + 100 + it) }
        ids.forEachIndexed { i, m -> fx.frame("g$i", (SEC + 100 + i) * 1000 + 5) }
        builder.build(ids)
        val rows = ids.mapIndexed { i, m -> row(m, "g$i", at = (SEC + 100 + i) * 1000 + 5) }
        assertEquals(450, imp().run(rows).written); assertEquals(450, dao.count())
    }
}
