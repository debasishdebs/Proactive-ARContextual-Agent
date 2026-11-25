package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/** WP12a: the Scene centroid producer on the real database (synthetic vectors only). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneCentroidProducerTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val moments = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private var on = true
    private var tick = 1_000L
    private val space = SceneCentroidProducer.CentroidSpace("space_1", "nomic-fp", 4)
    private var spaceList = listOf(space)
    private val producer = SceneCentroidProducer(db, enabled = { on }, spaces = { spaceList }, clock = { tick++ })
    private val builder = SceneBuilder(db, enabled = { true }, clock = { tick++ }, centroids = producer)
    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    @After fun close() = db.close()

    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)
    private suspend fun rebuild() = builder.rebuildWindow(t(0), t(3599))

    private suspend fun photo(sec: Long, v: FloatArray?, dupOf: String? = null, space: String = "space_1") {
        fx.frame("f$sec", t(sec) + 5)
        if (dupOf != null) db.frameMetaDao().upsert(db.frameMetaDao().get("f$sec")!!.copy(nearDupOf = dupOf))
        if (v != null) db.frameVectorDao().upsert(listOf(FrameVectorEntity("f$sec", space, VectorCodec.encode(v))))
        moments.build(listOf(mid(sec)))
    }

    private fun unit(vararg x: Float): FloatArray { val n = sqrt(x.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(x.size) { x[it] / n } }

    @Test fun offByDefaultWritesNothing() = runBlocking<Unit> {
        on = false
        photo(0, unit(1f, 0f, 0f, 0f))
        rebuild()
        assertEquals(0, db.sceneCentroidDao().count())
        assertEquals(SceneCentroidProducer.Report.NONE, SceneCentroidProducer(db).refresh(listOf(sid(0))))
        assertEquals(0, db.sceneCentroidDao().count())
    }

    @Test fun theMeanIsL2NormalisedAndStoredLikeAFrameVector() = runBlocking<Unit> {
        photo(0, unit(1f, 0f, 0f, 0f)); photo(1, unit(0f, 1f, 0f, 0f))
        rebuild()
        val row = db.sceneCentroidDao().get(sid(0), "nomic-fp")!!
        val v = VectorCodec.decode(row.vector)
        assertEquals(4, row.dim); assertEquals(16, row.vector.size); assertEquals(2, row.memberCount)
        assertEquals(0.7071f, v[0], 1e-4f); assertEquals(0.7071f, v[1], 1e-4f); assertEquals(0f, v[2], 0f)
        assertEquals(1.0, v.sumOf { (it * it).toDouble() }, 1e-5)
        assertEquals(db.sceneDao().scene(sid(0))!!.membershipRev, row.membershipRev)
        // little endian, like frame_vector
        assertEquals(java.nio.ByteBuffer.wrap(row.vector).order(java.nio.ByteOrder.LITTLE_ENDIAN).getFloat(0), v[0], 0f)
    }

    @Test fun aDuplicateGroupCountsOnce() = runBlocking<Unit> {
        // f0 is the representative; f1 and f2 are marked as near duplicates of it and carry (slightly) different vectors; f3 is a different photo.
        photo(0, unit(1f, 0f, 0f, 0f)); photo(1, unit(1f, 0.1f, 0f, 0f), dupOf = "f0"); photo(2, unit(1f, 0f, 0.1f, 0f), dupOf = "f0"); photo(3, unit(0f, 1f, 0f, 0f))
        rebuild()
        val row = db.sceneCentroidDao().get(sid(0), "nomic-fp")!!
        assertEquals("two groups: {f0,f1,f2} and {f3}", 2, row.memberCount)
        val v = VectorCodec.decode(row.vector)
        assertEquals("the representative's own vector stands for its group (equal weight with f3)", v[0], v[1], 1e-5f)
        assertEquals(0f, v[2], 0f)
    }

    @Test fun aGroupWhoseRepresentativeHasNoVectorUsesAMember() = runBlocking<Unit> {
        photo(0, null); photo(1, unit(1f, 0f, 0f, 0f), dupOf = "f0"); photo(2, unit(1f, 0f, 0f, 0f), dupOf = "f0")
        rebuild()
        assertEquals(1, db.sceneCentroidDao().get(sid(0), "nomic-fp")!!.memberCount)
    }

    @Test fun aSceneWithoutVectorsGetsNoRowAndAnEmptyOrNanVectorIsNeverWritten() = runBlocking<Unit> {
        photo(0, null)
        photo(100, floatArrayOf(Float.NaN, 0f, 0f, 0f)); photo(200, floatArrayOf(0f, 0f, 0f, 0f)); photo(300, unit(1f, 0f))   // NaN, zero norm, wrong width
        builder.rebuildWindow(t(0), t(3599))
        assertEquals(0, db.sceneCentroidDao().count())
        assertEquals(0, SceneCentroidProducer.build(emptyList(), 4)?.size ?: 0)
        assertNull(SceneCentroidProducer.build(listOf(unit(1f, 0f, 0f, 0f), unit(-1f, 0f, 0f, 0f)), 4))   // cancels to a zero vector
    }

    @Test fun anotherSpaceIsAnotherRowAndASpaceWithoutVectorsGetsNone() = runBlocking<Unit> {
        spaceList = listOf(space, SceneCentroidProducer.CentroidSpace("space_2", "siglip2-fp", 4))
        photo(0, unit(1f, 0f, 0f, 0f))
        rebuild()
        assertEquals(listOf("nomic-fp"), db.sceneCentroidDao().getForScene(sid(0)).map { it.space })
        photo(1, unit(0f, 0f, 1f, 0f), space = "space_2")
        rebuild()
        assertEquals(listOf("nomic-fp", "siglip2-fp"), db.sceneCentroidDao().getForScene(sid(0)).map { it.space })
    }

    @Test fun aRebuildThatChangesMembershipRewritesTheRowAtTheNewRevisionAndALaterOneLeavesItAlone() = runBlocking<Unit> {
        photo(0, unit(1f, 0f, 0f, 0f))
        rebuild()
        val first = db.sceneCentroidDao().get(sid(0), "nomic-fp")!!
        assertEquals(1, first.membershipRev)
        rebuild()
        assertEquals("a current row is not rewritten", first, db.sceneCentroidDao().get(sid(0), "nomic-fp"))
        photo(1, unit(0f, 1f, 0f, 0f))
        rebuild()
        val second = db.sceneCentroidDao().get(sid(0), "nomic-fp")!!
        assertEquals(2, second.membershipRev); assertEquals(2, second.memberCount)
        assertEquals(db.sceneDao().scene(sid(0))!!.membershipRev, second.membershipRev)
        assertEquals("one row per (scene, space)", 1, db.sceneCentroidDao().count())
    }

    @Test fun aStaleRowIsReplacedAndTheRowAlwaysCarriesTheScenesCurrentRevision() = runBlocking<Unit> {
        photo(0, unit(1f, 0f, 0f, 0f))
        rebuild()
        // a Forget shrink bumps the revision behind the producer's back
        db.sceneDao().upsertScene(db.sceneDao().scene(sid(0))!!.let { it.copy(membershipRev = it.membershipRev + 5) })
        val r = producer.refresh(listOf(sid(0)))
        assertEquals(1, r.written)
        assertEquals(6, db.sceneCentroidDao().get(sid(0), "nomic-fp")!!.membershipRev)
    }

    @Test fun aGoneSceneWritesNothing() = runBlocking<Unit> {
        assertEquals(1, producer.refresh(listOf("S-nothing")).gone)
        assertEquals(0, db.sceneCentroidDao().count())
    }

    @Test fun aProducerFailureNeverFailsTheRebuild() = runBlocking<Unit> {
        spaceList = emptyList()
        val boom = SceneBuilder(db, enabled = { true }, clock = { tick++ }, centroids = SceneCentroidProducer(db, enabled = { true }, spaces = { error("no embedder") }))
        photo(0, unit(1f, 0f, 0f, 0f))
        val r = boom.rebuildWindow(t(0), t(3599))
        assertEquals(1, r.stored)
        assertNotNull(db.sceneDao().scene(sid(0)))
        assertEquals(0, db.sceneCentroidDao().count())
        assertTrue(true)
    }
}
