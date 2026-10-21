package com.rokid.context.work.frames.scene

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.retrieval.VectorCodec
import com.rokid.context.work.budget.PhonePowerPolicy
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.ThermalStatusProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SceneIndexerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    @After fun close() = db.close()

    private val T = 1_000_000_000L
    private var now = T
    private var fg = true
    private var thermal = PhoneThermal.NONE
    private val power = PhonePowerPolicy({ now }, { fg }, ThermalStatusProvider { thermal })

    /** Photo byte 0 picks the vector; vectors are unit, 6-dim: dims 0-1 scene A plane, 2-3 scene B plane, 4 free, 5 watch axis. */
    private fun plane(axis: Int, deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        return FloatArray(6).also { it[axis] = cos(r).toFloat(); it[axis + 1] = sin(r).toFloat() }
    }
    private val vecs = mutableMapOf<Int, FloatArray>()
    private val words = mutableMapOf<Int, Int>()
    private val failing = mutableSetOf<Int>()
    private var watchVec = FloatArray(6).also { it[5] = 1f }
    private var ocrCalls = 0

    private fun code(c: Int, v: FloatArray, w: Int = 0): ByteArray { vecs[c] = v; words[c] = w; return byteArrayOf(c.toByte()) }
    private val photoBytes = mutableMapOf<String, ByteArray>()
    private val photosCalled = mutableListOf<List<String>>()
    private var beforePhotos: suspend (List<String>) -> Unit = {}

    private fun embed(target: String): suspend (List<ByteArray>) -> List<FloatArray> = { jpegs ->
        jpegs.map { b ->
            val c = b[0].toInt()
            if (c in failing) error("decode failed")
            vecs[c]!!.let { v -> if (target == "building") FloatArray(v.size) { i -> v[(i + 1) % v.size] } else v }
        }
    }
    private fun target(id: String, active: Boolean, kind: String = id) =
        ImageSpaceTarget(id, "fp_$id", 6, embed(kind), { watchVec }, active)

    private var targets: List<ImageSpaceTarget> = listOf(target("sA", true))
    private val texts = TextPresence { jpeg -> ocrCalls++; words[jpeg[0].toInt()] }
    private val watchStore = WatchListStore(ctx.getSharedPreferences("si_watch_${System.nanoTime()}", Context.MODE_PRIVATE))
    private var tuning = SceneTuning()

    private fun indexer(withPower: Boolean = true) = SceneIndexer(
        db, { targets }, texts, WatchVectors(watchStore), if (withPower) power else null,
        { ids -> photosCalled += ids; beforePhotos(ids); ids.mapNotNull { id -> photoBytes[id]?.let { id to it } }.toMap() },
        { now }, { tuning }, { fg },
    )

    private suspend fun seed(id: String, ts: Long, bytes: ByteArray?, dwell: Boolean = false, motion: String = "STILL", blur: Float? = null) {
        db.frameDao().insertIgnore(FrameEntity(id, ts, "/x/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, motion, dwell, blur, null, null, null, null, null, null, null, null, now, null))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity(id, "PENDING", 0, null, null, null, 0)))
        if (bytes != null) photoBytes[id] = bytes
    }
    private suspend fun st(id: String) = db.frameStateDao().get(id)!!
    private suspend fun meta(id: String) = db.frameMetaDao().get(id)!!

    @Test fun dwellRoutesFastEvenWithoutVectors() = runBlocking {
        targets = emptyList()
        seed("d", T, null, dwell = true)
        val r = indexer().routeBatch()
        assertEquals(1, r.routed)
        assertEquals("PENDING", st("d").state)
        assertNotNull(meta("d").routedAt); assertNull(meta("d").clusterId)
        assertEquals(0.4f, meta("d").salience!!, 0.001f)
    }

    @Test fun dwellBeforeSalienceEvenWhenDuplicate() = runBlocking {
        val b = code(1, plane(0, 0.0))
        seed("a", T, b); seed("d", T + 1000, b, dwell = true)
        indexer().routeBatch()
        assertEquals("PENDING", st("d").state); assertNull(meta("d").nearDupOf)
    }

    @Test fun noImageSpaceKeepsWaveOneBehaviour() = runBlocking {
        targets = emptyList()
        seed("a", T, code(1, plane(0, 0.0))); seed("b", T + 1000, code(2, plane(2, 0.0)))
        val r = indexer().routeBatch()
        assertEquals(2, r.routed)
        listOf("a", "b").forEach { assertEquals("PENDING", st(it).state); assertNotNull(meta(it).routedAt); assertNull(meta(it).clusterId) }
        assertNull(db.frameClusterDao().open())
        assertEquals(0, ocrCalls)
        assertEquals(0, photosCalled.sumOf { it.size })
    }

    @Test fun vectorsWrittenToActiveAndBuilding() = runBlocking {
        targets = listOf(target("sA", true), target("sB", false, kind = "building"))
        seed("a", T, code(1, plane(0, 0.0)))
        indexer().routeBatch()
        val va = VectorCodec.decode(db.frameVectorDao().get("a", "sA")!!.blob)
        val vb = VectorCodec.decode(db.frameVectorDao().get("a", "sB")!!.blob)
        assertEquals(1f, va[0], 1e-6f); assertEquals(1f, vb[5], 1e-6f)
        assertEquals("IN_CLUSTER", st("a").state)
    }

    @Test fun dupMarkedWithLink() = runBlocking {
        val b = code(1, plane(0, 0.0))
        seed("a", T, b); seed("b", T + 60_000, b)
        indexer().routeBatch()
        assertEquals("IN_CLUSTER", st("a").state)
        assertEquals("NEAR_DUP", st("b").state); assertEquals("semantic_dup", st("b").lastError)
        assertEquals("a", meta("b").nearDupOf)
        assertNull(meta("b").clusterId)
        assertNotNull(db.frameVectorDao().get("b", "sA")) // the vector is kept
    }

    @Test fun dupIsOnlyWithinTenMinutes() = runBlocking {
        val b = code(1, plane(0, 0.0))
        seed("a", T, b); seed("b", T + 11 * 60_000, b)
        indexer().routeBatch()
        assertNotEquals("NEAR_DUP", st("b").state)
    }

    @Test fun salientDupCaptionQueued() = runBlocking {
        watchVec = plane(0, 0.0)
        val b = code(1, plane(0, 0.0), w = 5)
        seed("a", T, b); seed("b", T + 60_000, b)
        indexer().routeBatch()
        assertEquals("PENDING", st("a").state)           // salient (watch + text + novelty): per-photo
        assertEquals("CAPTION_QUEUED", st("b").state)    // salient dup: caption queued
        assertEquals("a", meta("b").nearDupOf)
    }

    @Test fun clusterOpenedJoinedAndClosedOnDrift() = runBlocking {
        seed("a1", T, code(1, plane(0, 0.0))); seed("a2", T + 10_000, code(2, plane(0, 25.0)))
        seed("b1", T + 20_000, code(3, plane(2, 0.0))); seed("b2", T + 30_000, code(4, plane(2, 25.0)))
        indexer().routeBatch()
        val c1 = meta("a1").clusterId!!
        assertEquals(c1, meta("a2").clusterId)
        val first = db.frameClusterDao().get(c1)!!
        assertEquals("CLOSED", first.state); assertEquals("drift", first.closeReason); assertNotNull(first.closedAt)
        assertEquals(setOf("a1", "a2"), org.json.JSONArray(first.repFrameIds).let { a -> (0 until a.length()).map { a.getString(it) } }.toSet())
        val c2 = meta("b1").clusterId!!
        assertNotEquals(c1, c2); assertEquals(c2, meta("b2").clusterId)
        val second = db.frameClusterDao().get(c2)!!
        assertEquals("OPEN", second.state); assertEquals(2, second.memberCount)
        listOf("a1", "a2", "b1", "b2").forEach { assertEquals("IN_CLUSTER", st(it).state) }
        assertEquals(setOf("b1", "b2"), db.frameMetaDao().members(c2).map { it.frameId }.toSet())
    }

    @Test fun closeAgedOnFiveMinutesAndGap() = runBlocking {
        val ix = indexer()
        assertEquals(false, ix.closeAged())
        seed("g1", T, code(1, plane(0, 0.0)))
        ix.routeBatch()
        now = T + 100_000; assertEquals(false, ix.closeAged())
        now = T + 121_000; assertEquals(true, ix.closeAged())
        assertEquals("gap", db.frameClusterDao().get(meta("g1").clusterId!!)!!.closeReason)
        assertNull(db.frameClusterDao().open())
        // age: frames every 100 s keep the gap open; at 300 s the age rule closes it
        val t2 = T + 20 * 60_000L
        seed("e1", t2, code(2, plane(2, 0.0))); seed("e2", t2 + 100_000, code(3, plane(2, 24.0))); seed("e3", t2 + 200_000, code(4, plane(2, 48.0)))
        now = t2 + 200_000; ix.routeBatch()
        now = t2 + 299_000; assertEquals(false, ix.closeAged())
        now = t2 + 300_000; assertEquals(true, ix.closeAged())
        val c = db.frameClusterDao().get(meta("e1").clusterId!!)!!
        assertEquals("age", c.closeReason); assertEquals(3, c.memberCount)
    }

    @Test fun outOfOrderBurstStartsNewCluster() = runBlocking {
        val ix = indexer()
        seed("n", T, code(1, plane(0, 0.0))); ix.routeBatch()
        seed("early", T - 70_000, code(2, plane(2, 0.0))); ix.routeBatch()
        val c1 = db.frameClusterDao().get(meta("n").clusterId!!)!!
        assertEquals("CLOSED", c1.state); assertEquals("out_of_order", c1.closeReason)
        assertNotEquals(c1.clusterId, meta("early").clusterId)
        assertEquals("OPEN", db.frameClusterDao().get(meta("early").clusterId!!)!!.state)
    }

    @Test fun moderateRoutesOnlyDwell() = runBlocking {
        thermal = PhoneThermal.MODERATE
        seed("d", T, null, dwell = true); seed("x", T + 1000, code(1, plane(0, 0.0)))
        val r = indexer().routeBatch()
        assertEquals(1, r.routed); assertNotNull(r.retryInMs)
        assertNotNull(meta("d").routedAt); assertNull(meta("x").routedAt)
        assertEquals("PENDING", st("d").state); assertEquals("PENDING", st("x").state)
        assertEquals(0, db.frameVectorDao().countForSpace("sA"))
    }

    @Test fun severeRoutesNothing() = runBlocking {
        thermal = PhoneThermal.SEVERE
        seed("d", T, null, dwell = true); seed("x", T + 1000, code(1, plane(0, 0.0)))
        val r = indexer().routeBatch()
        assertEquals(0, r.routed)
        assertNull(meta("d").routedAt); assertNull(meta("x").routedAt)
    }

    @Test fun backgroundBatchesEightOrFifteenSeconds() = runBlocking {
        fg = false
        val ix = indexer()
        (0 until 3).forEach { seed("p$it", T + it * 1000L, code(10 + it, plane(0, it * 30.0))) }
        now = T + 2_000
        val wait = ix.routeBatch()
        assertEquals(0, wait.routed); assertEquals(13_000L, wait.retryInMs)
        now = T + 15_000
        assertEquals(3, ix.routeBatch().routed)
        // eight waiting: no wait
        val t2 = T + 20 * 60_000L; now = t2
        (0 until 8).forEach { seed("q$it", t2 + it, code(20 + it, plane(2, it * 30.0))) }
        assertEquals(8, ix.routeBatch().routed)
        // a dwell frame waiting: no wait
        seed("dw", t2 + 100, null, dwell = true)
        assertEquals(1, ix.routeBatch().routed)
    }

    @Test fun askClaimedFrameKeepsItsClaim() = runBlocking {
        seed("a", T, code(1, plane(0, 0.0)))
        beforePhotos = { ids -> db.frameStateDao().claimForAsk(ids, "ask-claim", now) }
        indexer().routeBatch()
        val s = st("a")
        assertEquals("EXTRACTING", s.state); assertEquals("ask-claim", s.claimId)
        assertNotNull(meta("a").routedAt); assertNull(meta("a").clusterId)
        assertNull(db.frameClusterDao().open()?.takeIf { it.memberCount > 0 })
    }

    @Test fun unembeddableAfterTwoFailures() = runBlocking {
        failing += 1
        seed("bad", T, code(1, plane(0, 0.0))); seed("ok", T + 1000, code(2, plane(2, 0.0)))
        val ix = indexer()
        val r1 = ix.routeBatch()
        assertEquals(1, r1.routed); assertNull(meta("bad").routedAt); assertNotNull(r1.retryInMs)
        assertNotNull(meta("ok").routedAt)
        val r2 = ix.routeBatch()
        assertEquals(1, r2.routed)
        assertEquals("unembeddable", meta("bad").imageVectorSpace); assertNotNull(meta("bad").routedAt)
        assertEquals("PENDING", st("bad").state)
    }

    @Test fun missingPhotoRoutesFast() = runBlocking {
        seed("gone", T, null)
        assertEquals(1, indexer().routeBatch().routed)
        assertEquals("PENDING", st("gone").state); assertNotNull(meta("gone").routedAt)
    }

    @Test fun restartRebuildsCentroid() = runBlocking {
        seed("a1", T, code(1, plane(0, 0.0))); seed("a2", T + 10_000, code(2, plane(0, 25.0)))
        indexer().routeBatch()
        val c1 = meta("a1").clusterId!!
        seed("b1", T + 20_000, code(3, plane(2, 0.0))); seed("b2", T + 30_000, code(4, plane(2, 25.0)))
        indexer().routeBatch() // a fresh instance: nothing in memory
        assertEquals("CLOSED", db.frameClusterDao().get(c1)!!.state)
        assertEquals("drift", db.frameClusterDao().get(c1)!!.closeReason)
        assertNotEquals(c1, meta("b1").clusterId); assertEquals(meta("b1").clusterId, meta("b2").clusterId)
    }

    @Test fun restartJoinsTheSameOpenCluster() = runBlocking {
        seed("a1", T, code(1, plane(0, 0.0)))
        indexer().routeBatch()
        seed("a2", T + 10_000, code(2, plane(0, 25.0)))
        indexer().routeBatch()
        assertEquals(meta("a1").clusterId, meta("a2").clusterId)
        assertEquals(2, db.frameClusterDao().get(meta("a1").clusterId!!)!!.memberCount)
    }

    @Test fun logsHaveNoText() = runBlocking {
        ShadowLog.reset()
        seed("a", T, code(1, plane(0, 0.0), w = 7)); seed("b", T + 1000, code(2, plane(2, 0.0)))
        indexer().routeBatch()
        val lines = ShadowLog.getLogsForTag("PcScene").map { it.msg }
        assertTrue(lines.isNotEmpty())
        lines.forEach { assertTrue(it, Regex("routed=\\d+ fast=\\d+ dup=\\d+ clustered=\\d+ closed=\\S+").matches(it)) }
        // nothing else leaks through other tags either
        assertTrue(ShadowLog.getLogs().none { it.msg.contains("/x/") })
    }
}
