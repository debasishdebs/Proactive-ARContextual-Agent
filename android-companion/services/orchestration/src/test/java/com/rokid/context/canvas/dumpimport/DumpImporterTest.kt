package com.rokid.context.canvas.dumpimport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ModelAttribution
import com.rokid.context.memory.db.AttributionKind
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import com.rokid.context.memory.moment.MomentId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DumpImporterTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    private val sec = 1_700_000_000L          // an OLD second: never "now"
    private val id = MomentId.format(sec)
    private val start = sec * 1000
    private val epoch = DeletionEpoch("i", 1, 0)
    private var gen = 0L
    private var locOn = true
    private val intake = FrameIntake(db) { 42L }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val b64 = Base64.getEncoder().encodeToString(jpeg)

    private val frameWriter = ImportFrameWriter { f ->
        val rows = FrameRows(
            FrameMetaEntity(f.id, f.id, null, f.motion, f.dwell, f.blur, f.dhash, f.phash, f.width, null, null, null, null, null, 42L, routedAt = 42L, capturedAtMs = f.capturedAtMs, markRef = f.markRef),
            FrameStateEntity(f.id, "EXTRACTED", 0, null, null, null, 42L),
        )
        intake.commit(FrameEntity(f.id, f.capturedAtMs, "/imp/${f.id}.jpg", f.source, "", placeLabel = f.placeLabel), rows) == FrameIntake.Outcome.ADDED
    }
    private val memoryWriter = ImportMemoryWriter { m ->
        MemoryRepository(db.memoryDao()).addIgnore(MemoryEntity(m.id, m.frameId, m.text, m.type, m.tags.joinToString(" "), m.tier, m.confidence, m.review, false, m.createdAt, m.derived, ""))
            .also { db.modelAttributionDao().record(AttributionKind.MEMORY, m.id, ModelAttribution("imported"), 42L); db.frameSourceDao().insertAll(listOf(FrameSourceEntity(m.id, m.frameId, null, "FACT"))) }
    }
    private val recorder = MarkRecorder(db.canvasSlice3Dao(), { DaoFence(0, epoch, { gen }, { epoch }) }, { 5 })
    private fun importer() = DumpImporter(
        db, frameWriter, memoryWriter, { k, photo -> recorder.importMark(k.id, k.source, k.atMs, photo, k.createdAt) }, { ids -> MomentBuilder(db).build(ids) }, { locOn }, { gen }, { 777L },
    )

    private fun body(frameId: String = "f1", memId: String = "mem1", withLoc: Boolean = true, markId: String? = "mk:ep1:3", momentId: String = id, st: Long = start) = """
        {"items":[{"object_type":"moment","id":"$momentId","startMs":$st,
          "frames":[{"id":"$frameId","ts":${st + 10},"capturedAtMs":${st + 10},"source":"rokid_cxr","motion":"STILL","dwell":false,"blur":0.5,"width":360,"phash":"abcd","hasPhoto":true}],
          "memories":[{"id":"$memId","frameId":"$frameId","text":"a red door","type":"note","tags":"door red","tier":"SHORT","confidence":0.7,"reviewState":"OK","createdAt":${st + 500},"derived":false},
                     {"id":"gone","frameId":"$frameId","text":"stale","reviewState":"INVALIDATED"}],
          "marks":[${if (markId != null) """{"id":"$markId","atMs":${st + 20},"source":"tap"}""" else ""}],
          "placeCards":[{"id":"c1"}],
          ${if (withLoc) """"location":{"lat":12.5,"lon":77.25,"accuracyM":30.0,"source":"ZONE_STREAM","speedClass":"WALKING","method":"NEAREST","atMs":${st + 15},"fixAgeMs":5000},""" else ""}
          "photos":{"$frameId":"$b64"}}]}
    """.trimIndent().toByteArray()

    private fun run(b: ByteArray, dedup: Boolean = false, dry: Boolean = false) = runBlocking { importer().run(DumpImportParser.parse(b), dedup, dry) }
    private fun rows() = runBlocking { listOf(db.frameDao().all().size, db.memoryDao().all().size, db.momentLocationDao().count(), if (db.momentDao().moment(id) != null) 1 else 0, if (db.canvasSlice3Dao().label("mk:ep1:3") != null) 1 else 0) }

    @Test fun createsTheMomentWithOriginalIdTimeLocationAndImportedAttribution() = runBlocking<Unit> {
        val r = run(body())
        assertEquals(listOf("created" to "ok"), r.items)
        assertEquals(listOf(1, 1, 1, 1, 1), rows())
        val f = db.frameDao().all().single()
        assertEquals("original frame time, not now", start + 10, f.ts)
        assertEquals(start + 10, db.frameMetaDao().byIds(listOf("f1")).single().capturedAtMs)
        val m = db.momentDao().moment(id)!!
        assertTrue("memory text rides on the rebuilt Moment", (m.text ?: "").contains("a red door"))
        val l = db.momentLocationDao().of(listOf(id)).single()
        assertEquals(12.5, l.lat, 0.0); assertEquals(77.25, l.lon, 0.0); assertEquals(start + 15, l.atMs); assertEquals("IMPORTED", l.source)
        assertEquals("imported", db.modelAttributionDao().get(AttributionKind.MEMORY, "mem1")!!.model)
        assertEquals(1, r.frames); assertEquals(1, r.memories); assertEquals(1, r.marks); assertEquals(1, r.locations); assertEquals(1, r.skippedPlaceCards)
        assertNull("the invalidated memory is not imported", db.dumpDao().memoriesByIds(listOf("gone")).firstOrNull())
    }

    @Test fun conflictWithoutDedupSkipWithDedupAndIdempotent() {
        run(body())
        val again = run(body())
        assertEquals(listOf("conflict" to "exists"), again.items)
        val dedup = run(body(), dedup = true)
        assertEquals(listOf("skipped" to "dedup"), dedup.items)
        assertEquals(listOf(1, 1, 1, 1, 1), rows())
    }

    @Test fun dryRunWritesNothingAndReportsWhatWouldBeCreated() {
        val r = run(body(), dry = true)
        assertEquals(listOf("would_create" to "ok"), r.items)
        assertEquals(listOf(0, 0, 0, 0, 0), rows())
        assertEquals(0, runBlocking { db.modelAttributionDao().count() })
    }

    @Test fun aForgottenSecondIsNeverRecreated() = runBlocking<Unit> {
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id, 1)))
        val r = run(body())
        assertEquals(listOf("skipped" to "forgotten"), r.items)
        assertEquals(listOf(0, 0, 0, 0, 0), rows())
    }

    @Test fun locationSettingOffSavesNoLocation() {
        locOn = false
        run(body())
        assertEquals(listOf(1, 1, 0, 1, 1), rows())
    }

    @Test fun aForgottenImportedMomentLeavesNoRows() = runBlocking<Unit> {
        run(body())
        val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 99L }
        MomentForget(db, svc) { 99L }.forgetMoments(listOf(id))
        assertEquals(listOf(0, 0, 0, 0, 0), rows())
        // and a second import of the same second is refused (the Forget fence)
        assertEquals(listOf("skipped" to "forgotten"), run(body()).items)
    }

    @Test fun deleteAllRemovesTheImportedData() = runBlocking<Unit> {
        run(body())
        db.wipeDao().wipeMemory(99L)
        assertEquals(listOf(0, 0, 0, 0, 0), rows())
        assertEquals(0, db.modelAttributionDao().count())
    }

    @Test fun deleteAllDuringTheImportStopsIt() {
        val gone = DumpImporter(db, { gen++; frameWriter.store(it) }, memoryWriter, { k, photo -> recorder.importMark(k.id, k.source, k.atMs, photo, k.createdAt) }, { MomentBuilder(db).build(it) }, { true }, { gen })
        val r = runBlocking { gone.run(DumpImportParser.parse(body()), false, false) }
        assertTrue(r.stopped)
    }

    @Test fun badInputIsRejectedByTheParserNeverWritten() {
        fun bad(b: String) { try { DumpImportParser.parse(b.toByteArray()); fail("expected Bad: $b") } catch (e: DumpImportParser.Bad) { /* ok */ } }
        bad("not json"); bad("""{"items":[]}"""); bad("""{"items":"x"}"""); bad("""{"items":[1]}""")
        bad("""{"items":[{"object_type":"weird"}]}""")
        val good = String(body())
        bad(good.replace("\"id\":\"$id\"", "\"id\":\"zz\""))                         // bad id
        bad(good.replace("\"startMs\":$start", "\"startMs\":${start + 1000}"))      // time not the id's second
        bad(good.replace("\"capturedAtMs\":${start + 10}", "\"capturedAtMs\":${start + 5000}")) // outside the second
        bad(good.replace("\"lat\":12.5", "\"lat\":91"))                              // lat range
        bad(good.replace("\"accuracyM\":30.0", "\"accuracyM\":0"))                    // accuracy
        bad(good.replace("\"confidence\":0.7", "\"confidence\":7"))                   // confidence
        bad(good.replace("\"id\":\"f1\"", "\"id\":\"../x\""))                        // frame id charset
        bad(good.replace(b64, "!!notbase64!!"))                                       // photo not base64
        bad(good.replace(b64, Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))) // not an image
        bad(good.replace("\"mk:ep1:3\"", "\"nope\""))                                 // mark id
        bad(good.replace("\"tier\":\"SHORT\"", "\"tier\":\"HUGE\""))                  // tier
        bad(good.replace("\"photos\":{\"f1\"", "\"photos\":{\"zz\""))                  // photo for no frame
    }

    @Test fun scenesAndPinsAreStillSkipped() {
        val e = DumpImportParser.parse("""{"items":[{"object_type":"scene","id":"s"},{"object_type":"pin","id":"p"}]}""".toByteArray())
        val r = runBlocking { importer().run(e, false, false) }
        assertEquals(listOf("skipped" to "scene", "skipped" to "pin"), r.items)
    }

    // ---- standalone marks and the real mark kind ----
    private val markAt = start + 123
    private fun markBody(mid: String = "mk:ep9:7", source: String = "voice", moment: String? = null, at: Long = markAt, photo: String = "phone_mark_no_photo") =
        """{"items":[{"object_type":"mark","id":"$mid","atMs":$at,"source":"$source","name":"MOMENT","frameId":null,"momentId":${if (moment == null) "null" else "\u0022$moment\u0022"},"photo":"$photo","createdAt":${at + 5}}]}""".toByteArray()
    private fun markRows() = runBlocking { db.canvasSlice3Dao().labels().size }

    @Test fun standaloneMarkKeepsItsRealKindIdTimeAndPhotoState() = runBlocking<Unit> {
        val r = run(markBody())
        assertEquals(listOf("created" to "ok"), r.items); assertEquals(1, r.marks)
        val l = db.canvasSlice3Dao().label("mk:ep9:7")!!
        assertEquals("voice", l.source); assertEquals(markAt, l.atMs); assertEquals(markAt + 5, l.createdAt); assertEquals("phone_mark_no_photo", l.photo)
        assertNotNull("the second of the mark is rebuilt into a Moment", db.momentDao().moment(MomentId.ofMs(markAt)))
        val vm = run(markBody(mid = "vm:${markAt}:g", source = "retro"))
        assertEquals(listOf("created" to "ok"), vm.items); assertEquals("retro", db.canvasSlice3Dao().label("vm:$markAt:g")!!.source)
    }

    @Test fun standaloneMarkDedupConflictAndDryRun() {
        assertEquals(listOf("would_create" to "ok"), run(markBody(), dry = true).items); assertEquals(0, markRows())
        run(markBody())
        assertEquals(listOf("conflict" to "exists"), run(markBody()).items)
        assertEquals(listOf("skipped" to "dedup"), run(markBody(), dedup = true).items)
        assertEquals(1, markRows())
    }

    @Test fun aMarkThatNamesItsMomentIsRestoredByThatMomentNotAsAConflict() {
        assertEquals(listOf("skipped" to "in_moment"), run(markBody(moment = id)).items); assertEquals(0, markRows())
    }

    @Test fun momentMarkKeepsItsKind() = runBlocking<Unit> {
        run(String(body()).replace("\"atMs\":${start + 20},\"source\":\"tap\"", "\"atMs\":${start + 20},\"source\":\"phone_chat\",\"photo\":\"ok\"").toByteArray())
        val l = db.canvasSlice3Dao().label("mk:ep1:3")!!
        assertEquals("phone_chat", l.source); assertEquals("no_frame", l.photo) // its photo is not in this import
    }

    @Test fun standaloneMarkOfAForgottenSecondIsNeverRestored() = runBlocking<Unit> {
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(MomentId.ofMs(markAt), 1)))
        assertEquals(listOf("skipped" to "forgotten"), run(markBody()).items); assertEquals(0, markRows())
    }

    @Test fun standaloneMarkForgetAndDeleteAllLeaveZeroRows() = runBlocking<Unit> {
        run(markBody())
        val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 99L }
        MomentForget(db, svc) { 99L }.forgetMoments(listOf(MomentId.ofMs(markAt)))
        assertEquals("Forget of the second removes the mark", 0, markRows())
        run(markBody(mid = "mk:ep9:8", source = "tap", at = start + 5400))
        assertEquals(1, markRows())
        db.wipeDao().wipeMemory(99L)
        assertEquals("Delete all", 0, markRows())
    }

    @Test fun badMarkItemsAre400() {
        fun bad(b: ByteArray) { try { DumpImportParser.parse(b); fail() } catch (e: DumpImportParser.Bad) { /* ok */ } }
        bad(markBody(mid = "nope")); bad(markBody(source = "weird")); bad(markBody(moment = "zz"))
        bad(String(markBody()).replace("\"atMs\":$markAt", "\"atMs\":\"x\"").toByteArray()); bad(String(markBody()).replace("\"atMs\":$markAt,", "").toByteArray())
    }

    @Test fun batchCapIs50() {
        val one = """{"object_type":"scene","id":"s"}"""
        try { DumpImportParser.parse("""{"items":[${List(51) { one }.joinToString(",")}]}""".toByteArray()); fail() } catch (e: DumpImportParser.Bad) { assertEquals(-1, e.index) }
    }

    @Test fun anImportedFrameIsAnOrdinaryFrameForRetentionAndZonePaths() = runBlocking<Unit> {
        run(body())
        val f = db.frameDao().all().single()
        assertTrue("keeps the rokid source prefix retention and the pipeline key on", f.source.startsWith("rokid"))
        assertNotNull(db.frameMetaDao().byIds(listOf(f.id)).single().capturedAtMs)
        assertEquals("EXTRACTED: no description or summary model call can claim it", "EXTRACTED", db.frameStateDao().get(f.id)!!.state)
    }
}
