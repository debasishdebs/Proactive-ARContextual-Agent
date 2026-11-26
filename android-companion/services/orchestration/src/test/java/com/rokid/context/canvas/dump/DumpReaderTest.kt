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
        assertNull(runBlocking { reader().photo("f6") }); // review2 #9: the old `photo("f5").let { null }` asserted nothing; the real Forget cascade is exercised by realForgetOperationsHide... below
        assertEquals(1, reader().manifest().getValue("counts").jsonObject["moments"]!!.jsonPrimitive.content.toInt())
    }

    /** review2 #2: a HIDDEN (stale) derived value and an INVALIDATED memory are never exported, whatever the forgetV2 flag. */
    @Test fun staleDerivedValuesAndInvalidatedMemoriesAreNotExported() = runBlocking {
        seed(1, 2)
        db.openHelper.writableDatabase.execSQL("UPDATE memory SET text = 'INVALIDATED-SECRET', reviewState = 'INVALIDATED' WHERE id = 'm2'") // invalidated AFTER the Moment was built (a Forget with forgetV2 off keeps the text)
        db.objectDao().upsertDerived(ObjectDerivedEntity("MOMENT", id(1), "CAPTION", "STALE-CAPTION-SECRET", null, 3, 50L, 9))
        db.sceneDao().replace(SceneEntity("s1", 1, MS, MS + 1000, MS + 3000, "CAP_5_MIN", null, null, "WALKING", 1, 9), listOf(ScenePartEntity("s1", "MOMENT", id(1), "h", MS + 1000), ScenePartEntity("s1", "MOMENT", id(2), "h", MS + 2000)))
        db.objectDao().upsertDerived(ObjectDerivedEntity("SCENE", "s1", "SUMMARY", "STALE-SCENE-SECRET", null, 3, 50L, 9))
        val r = reader()
        val text = all(r).joinToString { it.json.toString() } + r.scenes(0, Long.MAX_VALUE, null, 9).joinToString { it.json.toString() }
        assertFalse(text, "STALE-SCENE-SECRET" in text || "STALE-CAPTION-SECRET" in text || "INVALIDATED-SECRET" in text)
        assertEquals("a fresh memory is still exported", true, "memory text 1" in text)
    }

    /** review2 #9: REAL Forget operations (not hand-made tombstones), both flag states: nothing forgotten or hidden reaches any dump answer. */
    @Test fun realForgetOperationsHideTheirContentFromEveryDumpAnswerWithBothFlagStates() {
        try {
            for (v2 in listOf(false, true)) {
                com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = v2
                val d = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
                try {
                    val f = MomentFixture(d)
                    runBlocking {
                        listOf(1, 2).forEach { s -> f.frame("f$s", MS + s * 1000L); f.memory("m$s", "f$s", text = "memory text $s", createdAt = MS + s * 1000L) }
                        MomentBuilder(d).build(listOf(id(1), id(2)))
                        d.sceneDao().replace(SceneEntity("s1", 1, MS, MS + 1000, MS + 3000, "CAP_5_MIN", null, null, "WALKING", 1, 9), listOf(ScenePartEntity("s1", "MOMENT", id(1), "h", MS + 1000), ScenePartEntity("s1", "MOMENT", id(2), "h", MS + 2000)))
                        d.objectDao().upsertDerived(ObjectDerivedEntity("SCENE", "s1", "SUMMARY", "OLD-SCENE-SUMMARY", null, 3, null, 9))
                        val before = DumpReader(d).moments(0, Long.MAX_VALUE, null, 200).size
                        com.rokid.context.memory.forget.ForgetService(d, com.rokid.context.memory.ProposalRepository(d.proposalDao(), d.actionDao(), d)) { 5_000 }.forgetMoment("f1")
                        MomentBuilder(d).build(listOf(id(1), id(2))) // the projection is rebuilt after a Forget, as the builder pass does
                        val r = DumpReader(d, { pins }, "9.9.9", { 1234L }, { jpeg })
                        val text = r.moments(0, Long.MAX_VALUE, null, 200).joinToString { it.json.toString() } + r.scenes(0, Long.MAX_VALUE, null, 9).joinToString { it.json.toString() }
                        assertNull("v2=$v2: the forgotten photo is not served", r.photo("f1"))
                        assertNotNull("v2=$v2: the surviving photo is served", r.photo("f2"))
                        assertFalse("v2=$v2: forgotten text exported: $text", "memory text 1" in text || "OLD-SCENE-SUMMARY" in text)
                        assertTrue("v2=$v2: the survivor stays: before=$before ${d.momentDao().allMoments().map { it.momentId }} $text", "memory text 2" in text)
                    }
                } finally { d.close() }
            }
        } finally { com.rokid.context.memory.forget.lifecycle.ForgetV2.testOverride = null }
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

    // v18 WP11a: model and modelVersion come from `model_attribution` (null when absent); the manifest stops listing them as absent once rows exist
    @Test fun modelAndModelVersionComeFromTheAttributionTableAndAreNullWhenAbsent() = runBlocking {
        seed(5, 6)
        db.objectDao().upsertDerived(ObjectDerivedEntity("MOMENT", id(5), "CAPTION", "a caption", null, 3, null, 9))
        db.objectDao().upsertDerived(ObjectDerivedEntity("MOMENT", id(6), "CAPTION", "other caption", null, 3, null, 9))
        assertTrue("no rows yet: still absent", "model_name" in reader().manifest()["fieldsAbsent"]!!.jsonArray.map { it.jsonPrimitive.content })
        db.modelAttributionDao().upsert(com.rokid.context.memory.db.ModelAttributionEntity("MEMORY", "m5", "vision-x", "2026-11", "4", 9))
        db.modelAttributionDao().upsert(com.rokid.context.memory.db.ModelAttributionEntity("DERIVED", "MOMENT:${id(5)}:CAPTION", "text-y", null, null, 9))
        val items = all().associate { it.json["id"]!!.jsonPrimitive.content to it.json }
        val mem5 = items.getValue(id(5))["memories"]!!.jsonArray.single().jsonObject
        assertEquals("vision-x", mem5["model"]!!.jsonPrimitive.content); assertEquals("2026-11", mem5["modelVersion"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, items.getValue(id(6))["memories"]!!.jsonArray.single().jsonObject["model"]); assertEquals(JsonNull, items.getValue(id(6))["memories"]!!.jsonArray.single().jsonObject["modelVersion"])
        val d5 = items.getValue(id(5))["derived"]!!.jsonArray.single().jsonObject
        assertEquals("text-y", d5["model"]!!.jsonPrimitive.content); assertEquals(JsonNull, d5["modelVersion"])
        assertEquals(JsonNull, items.getValue(id(6))["derived"]!!.jsonArray.single().jsonObject["model"])
        val m = reader().manifest(); val absent = m["fieldsAbsent"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("transcript_text"), absent); assertEquals(2, m["counts"]!!.jsonObject["modelAttributions"]!!.jsonPrimitive.content.toInt())
        assertFalse("the attribution never carries text", m.toString().contains("a caption"))
    }

    @Test fun theLocationAppearsOnlyInTheMomentItemsNeverInTheOtherDumpAnswers() = runBlocking {
        seed(1)
        db.momentLocationDao().insertIfAbsent(loc(id(1), "f1", lat = 41.123456, lon = -73.654321))
        fx.mark("mk:1:1", MS + 1000)
        db.frameVectorDao().upsert(listOf(FrameVectorEntity("f1", "siglip2-x", VectorCodec.encode(floatArrayOf(1f)))))
        val r = reader()
        val others = listOf(r.manifest().toString(), r.scenes(0, Long.MAX_VALUE, null, 9).toString(), r.marks(0, Long.MAX_VALUE, null, 9).joinToString { it.json.toString() })
        others.forEach { assertFalse(it, "41.123456" in it || "73.654321" in it) }
        assertTrue("41.123456" in all(r).single().json.toString())
    }
}
