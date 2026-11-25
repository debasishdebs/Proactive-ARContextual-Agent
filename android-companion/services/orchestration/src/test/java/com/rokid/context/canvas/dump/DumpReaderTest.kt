package com.rokid.context.canvas.dump

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.canvas.moment.MomentFixture
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EmbeddingSpaceEntity
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameClusterEntity
import com.rokid.context.memory.db.FrameVectorEntity
import com.rokid.context.memory.db.MomentLocationEntity
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.db.ObjectDerivedEntity
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.SpaceState
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DumpReaderTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val builder = MomentBuilder(db)
    @After fun close() = db.close()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private var pins: List<UserPlace> = emptyList()
    private fun reader() = DumpReader(db, { pins }, "9.9.9", { 1234L }, { jpeg })
    private fun id(s: Int) = MomentId.format(SEC + s)

    private suspend fun seed(vararg secs: Int) { secs.forEach { s -> fx.frame("f$s", MS + s * 1000L); fx.memory("m$s", "f$s", text = "memory text $s", createdAt = MS + s * 1000L) }; builder.build(secs.map { id(it) }) }
    private fun all(r: DumpReader = reader()) = runBlocking { r.moments(0, Long.MAX_VALUE, null, 200) }
    private fun loc(m: String, anchor: String, lat: Double = 12.5, lon: Double = 77.25) = MomentLocationEntity(m, MS, "FRAME", anchor, lat, lon, 30.0, 5_000, "ZONE_STREAM", "WALKING", "INTERPOLATED", 7)

    @Test fun momentCarriesFramesMemoriesDerivedLocationAndPlaceName() = runBlocking {
        seed(5)
        db.momentLocationDao().insertIfAbsent(loc(id(5), "f5"))
        db.objectDao().upsertDerived(ObjectDerivedEntity("MOMENT", id(5), "CAPTION", "a caption", null, 3, null, 9))
        db.objectDao().upsertDerived(ObjectDerivedEntity("MOMENT", id(5), "EMBEDDING", null, "space_a", 2, 100L, 9))
        db.placeCardLogDao().put(PlaceCardLogEntity("pc1", MS + 5000, "wd:Q1", "Mirror Wall", "PLACE", null, "f5", null, "card-1", "SENT"))
        val m = all().single().json
        assertEquals(id(5), m["id"]!!.jsonPrimitive.content)
        assertEquals((SEC + 5) * 1000, m["startMs"]!!.jsonPrimitive.content.toLong()); assertEquals((SEC + 5) * 1000 + 999, m["endMs"]!!.jsonPrimitive.content.toLong())
        assertEquals("f5", m["frames"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("memory text 5", m["memories"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("OK", m["reviewState"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, m["memories"]!!.jsonArray.single().jsonObject["model"])
        val d = m["derived"]!!.jsonArray.map { it.jsonObject }.associateBy { it["field"]!!.jsonPrimitive.content }
        assertEquals("a caption", d.getValue("CAPTION")["value"]!!.jsonPrimitive.content); assertEquals("3", d.getValue("CAPTION")["builtRev"]!!.jsonPrimitive.content)
        assertEquals("true", d.getValue("EMBEDDING")["stale"]!!.jsonPrimitive.content); assertEquals("space_a", d.getValue("EMBEDDING")["embeddingSpace"]!!.jsonPrimitive.content)
        val l = m["location"]!!.jsonObject
        assertEquals(12.5, l["lat"]!!.jsonPrimitive.content.toDouble(), 0.0); assertEquals(77.25, l["lon"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("ZONE_STREAM", l["source"]!!.jsonPrimitive.content); assertEquals("WALKING", l["speedClass"]!!.jsonPrimitive.content); assertEquals("INTERPOLATED", l["method"]!!.jsonPrimitive.content)
        assertEquals("card-1", m["placeCards"]!!.jsonArray.single().jsonObject["cardId"]!!.jsonPrimitive.content)
        assertEquals("Mirror Wall", m["placeName"]!!.jsonPrimitive.content)
    }

    @Test fun pinNameWinsAndMomentWithoutLocationHasNull() = runBlocking {
        seed(5)
        pins = listOf(UserPlace("user:00000000-0000-0000-0000-000000000001", "My spot", "OTHER", 1.5, 2.5, momentId = id(5)))
        val m = all().single().json
        assertEquals(JsonNull, m["location"]); assertEquals("My spot", m["placeName"]!!.jsonPrimitive.content)
        assertEquals("pin", m["placeNames"]!!.jsonArray.first().jsonObject["source"]!!.jsonPrimitive.content)
    }

    @Test fun tombstonedMomentAndForgottenPhotoNeverAppear() = runBlocking {
        seed(5, 6)
        db.momentLocationDao().insertIfAbsent(loc(id(6), "f6"))
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id(5), 1)))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f6", 1))
        val items = all()
        assertEquals(listOf(id(6)), items.map { it.cursor })
        val m = items.single().json
        assertTrue("the forgotten photo is gone from the Moment", m["frames"]!!.jsonArray.isEmpty()); assertTrue("and its memory", m["memories"]!!.jsonArray.isEmpty())
        assertNull(runBlocking { reader().photo("f6") }); assertNull(runBlocking { reader().photo("f5").let { null } })
        assertEquals(1, reader().manifest().getValue("counts").jsonObject["moments"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun pagingByCursorAndTheDateWindow() = runBlocking {
        seed(1, 2, 3, 4, 5)
        val r = reader()
        val p1 = r.moments(0, Long.MAX_VALUE, null, 2); val p2 = r.moments(0, Long.MAX_VALUE, p1.last().cursor, 2); val p3 = r.moments(0, Long.MAX_VALUE, p2.last().cursor, 2)
        assertEquals((1..5).map { id(it) }, (p1 + p2 + p3).map { it.cursor }); assertEquals(1, p3.size)
        assertEquals(listOf(id(2), id(3)), r.moments((SEC + 2) * 1000, (SEC + 3) * 1000 + 999, null, 200).map { it.cursor })
    }

    @Test fun scenesListLiveMembersAndClusterMedoids() = runBlocking {
        seed(1, 2)
        db.frameMetaDao().upsert(db.frameMetaDao().get("f1")!!.copy(clusterId = "c1"))
        fx.memory("cm", null, text = "cluster summary")
        db.frameClusterDao().upsert(FrameClusterEntity("c1", MS, MS + 5000, "gap", "[\"f1\",\"gone\"]", "cm", "SUMMARIZED", 0, null, null, null, 1, "STILL", 2, MS + 2000))
        db.sceneDao().replace(SceneEntity("s1", 1, MS, MS + 1000, MS + 3000, "CAP_5_MIN", null, null, "WALKING", 1, 9), listOf(ScenePartEntity("s1", "MOMENT", id(1), "h", MS + 1000), ScenePartEntity("s1", "MOMENT", id(2), "h", MS + 2000)))
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id(2), 1)))
        val s = reader().scenes(0, Long.MAX_VALUE, null, 200).single().json
        assertEquals(listOf(id(1)), s["momentIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        val c = s["clusters"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("f1"), c["medoidFrameIds"]!!.jsonArray.map { it.jsonPrimitive.content }); assertEquals("cluster summary", c["summary"]!!.jsonPrimitive.content)
        assertEquals("WALKING", s["activity"]!!.jsonPrimitive.content); assertEquals("CAP_5_MIN", s["boundaryRule"]!!.jsonPrimitive.content)
        assertEquals(id(1), db.dumpDao().let { reader().moments(0, Long.MAX_VALUE, null, 200).first().json["id"]!!.jsonPrimitive.content })
        assertEquals("s1", all().first().json["sceneId"]!!.jsonPrimitive.content)
    }

    @Test fun marksPageAndPinsDropADeadMomentLink() = runBlocking {
        fx.mark("mk:1:1", MS + 1000); fx.mark("mk:1:2", MS + 2000); fx.mark("mk:1:2:i", MS + 2000, kind = "INTENT")
        val r = reader()
        val p1 = r.marks(0, Long.MAX_VALUE, null, 1); val p2 = r.marks(0, Long.MAX_VALUE, p1.last().cursor, 1)
        assertEquals(listOf("mk:1:1", "mk:1:2"), (p1 + p2).map { it.json["id"]!!.jsonPrimitive.content }); assertTrue(r.marks(0, Long.MAX_VALUE, p2.last().cursor, 5).isEmpty())
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id(9), 1)))
        pins = listOf(UserPlace("user:00000000-0000-0000-0000-000000000002", "Pin", "OTHER", 10.0, 20.0, note = "n", momentId = id(9)), UserPlace("user:00000000-0000-0000-0000-000000000001", "A", "OTHER", 1.0, 2.0))
        val p = r.pins()
        assertEquals(listOf("A", "Pin"), p.map { it["name"]!!.jsonPrimitive.content }); assertEquals(JsonNull, p[1]["momentId"]); assertEquals(10.0, p[1]["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun photoIsServedOnlyForALiveFrameWithImageBytes() = runBlocking {
        seed(1)
        val ok = reader().photo("f1")!!; assertEquals("image/jpeg", ok.contentType); assertTrue(ok.bytes.contentEquals(jpeg))
        assertNull(reader().photo("nope"))
        assertNull(DumpReader(db, readPhoto = { byteArrayOf(1, 2, 3, 4) }).photo("f1")) // not an image
        assertNull(DumpReader(db, readPhoto = { null }).photo("f1"))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1)); assertNull(reader().photo("f1"))
    }

    @Test fun vectorsPerSpaceDimDtypeAndForgottenFramesLeftOut() = runBlocking {
        seed(1, 2)
        val v = VectorCodec.encode(floatArrayOf(1f, -2f, 0.5f))
        val sig = "siglip2-b16-224@x|v|d=768"
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("f1", sig, v), FrameVectorEntity("f2", sig, v), FrameVectorEntity("f1", "space_n", v)))
        db.openHelper.writableDatabase.execSQL("INSERT INTO memory_vector(memoryId, spaceId, blob) VALUES ('m1', 'space_n', ?)", arrayOf<Any>(v))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f2", 1))
        val r = reader()
        val s = r.vectors("siglip2", null, 200, false)!!
        assertEquals(listOf("f1"), s.map { it.json["id"]!!.jsonPrimitive.content }); assertEquals("3", s[0].json["dim"]!!.jsonPrimitive.content); assertEquals("float32", s[0].json["dtype"]!!.jsonPrimitive.content)
        assertTrue(Base64.getDecoder().decode(s[0].json["data"]!!.jsonPrimitive.content).contentEquals(v))
        val n = r.vectors("nomic", null, 200, true)!!
        assertEquals(listOf("memory:m1", "frame:f1"), n.map { it.json["kind"]!!.jsonPrimitive.content + ":" + it.json["id"]!!.jsonPrimitive.content })
        val half = Base64.getDecoder().decode(n[0].json["data"]!!.jsonPrimitive.content)
        assertEquals("float16", n[0].json["dtype"]!!.jsonPrimitive.content)
        assertEquals(listOf(0x00, 0x3C, 0x00, 0xC0, 0x00, 0x38), half.map { it.toInt() and 0xFF }) // 1.0, -2.0, 0.5 as little-endian halves
        // paging across the memory and frame parts
        val a = r.vectors("nomic", null, 1, false)!!; val b = r.vectors("nomic", a.last().cursor, 1, false)!!; val c = r.vectors("nomic", b.last().cursor, 1, false)!!
        assertEquals(listOf("memory", "frame"), (a + b).map { it.json["kind"]!!.jsonPrimitive.content }); assertTrue(c.isEmpty())
        assertNull(r.vectors("other", null, 10, false))
    }

    @Test fun halfConversionRoundsAndHandlesTheEdges() {
        assertEquals(0x3C00, DumpReader.floatToHalf(1f)); assertEquals(0x7C00, DumpReader.floatToHalf(1e9f)); assertEquals(0xFC00, DumpReader.floatToHalf(-1e9f)); assertEquals(0, DumpReader.floatToHalf(0f))
        assertEquals(0x3555, DumpReader.floatToHalf(1f / 3f)); assertEquals(0x0001, DumpReader.floatToHalf(5.9604645e-8f)); assertTrue(DumpReader.floatToHalf(Float.NaN) and 0x7C00 == 0x7C00)
    }

    @Test fun manifestHasCountsRangeFieldsAndNoSecret() = runBlocking {
        seed(1, 2)
        db.momentLocationDao().insertIfAbsent(loc(id(1), "f1"))
        db.openHelper.writableDatabase.execSQL("INSERT INTO embedding_space(id, fingerprint, configJson, dim, state, canaryJson, createdAt) VALUES ('space_n', 'nomic-v1.5|d=3', '{\"apiKey\":\"SECRET-KEY-123\"}', 3, 'ACTIVE', '[]', 1)")
        pins = listOf(UserPlace("user:00000000-0000-0000-0000-000000000001", "A", "OTHER", 1.0, 2.0))
        val m = reader().manifest(); val text = m.toString()
        val c = m["counts"]!!.jsonObject
        assertEquals(2, c["moments"]!!.jsonPrimitive.content.toInt()); assertEquals(1, c["locations"]!!.jsonPrimitive.content.toInt()); assertEquals(1, c["pins"]!!.jsonPrimitive.content.toInt()); assertEquals(2, c["frames"]!!.jsonPrimitive.content.toInt())
        assertEquals(id(1), m["range"]!!.jsonObject["firstMomentId"]!!.jsonPrimitive.content); assertEquals("9.9.9", m["appVersion"]!!.jsonPrimitive.content)
        assertTrue(m["dump_id"]!!.jsonPrimitive.content.startsWith("dump-")); assertEquals(m["dump_id"], reader().manifest()["dump_id"])
        assertFalse("the space config (it may hold keys) is never dumped", "SECRET-KEY-123" in text || "apiKey" in text)
        assertTrue("model_name" in m["fieldsAbsent"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("nomic-v1.5|d=3", m["embeddingSpaces"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun theLocationAppearsOnlyInTheMomentItemsNeverInTheOtherDumpAnswers() = runBlocking {
        seed(1)
        db.momentLocationDao().insertIfAbsent(loc(id(1), "f1", lat = 41.123456, lon = -73.654321))
        fx.mark("mk:1:1", MS + 1000)
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("f1", "siglip2-x", VectorCodec.encode(floatArrayOf(1f)))))
        val r = reader()
        val others = listOf(r.manifest().toString(), r.scenes(0, Long.MAX_VALUE, null, 9).toString(), r.marks(0, Long.MAX_VALUE, null, 9).joinToString { it.json.toString() }, r.vectors("siglip2", null, 9, false)!!.joinToString { it.json.toString() })
        others.forEach { assertFalse(it, "41.123456" in it || "73.654321" in it) }
        assertTrue("41.123456" in all(r).single().json.toString())
    }
}
