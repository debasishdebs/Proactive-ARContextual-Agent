package com.rokid.context.canvas.dumpimport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.canvas.moment.SceneBuilder
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
import org.junit.Before
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.work.frames.place.PinMomentLinks
import com.rokid.context.work.frames.place.PlaceCardHistory
import com.rokid.context.work.frames.place.PlacePinning
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
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val rawPrefs get() = ctx.getSharedPreferences(UserPinStore.PREFS, android.content.Context.MODE_PRIVATE)
    private val pinStore get() = UserPinStore(rawPrefs)
    private var zoneOn = false
    private val pinning = PlacePinning(
        fix = { null }, zoneBlocked = { zoneOn }, zones = { emptyList() }, nearby = { _, _ -> emptyList() }, load = { pinStore.pins() }, save = { pinStore.write(it) },
        elapsed = { 0L }, momentAlive = { m -> db.momentDao().photoMomentsOf(listOf(m)).isNotEmpty() },
    )
    @Before fun clearPins() { rawPrefs.edit().clear().commit() }
    private var zoneList: List<com.rokid.context.core.model.zone.PauseZone>? = emptyList()
    private fun importer() = DumpImporter(
        db, frameWriter, memoryWriter, { k, photo -> recorder.importMark(k.id, k.source, k.atMs, photo, k.createdAt) }, { ids -> MomentBuilder(db).build(ids) }, { locOn }, { gen }, { 777L },
        importPin = { pinning.importPin(it) }, pinIds = { pinStore.pins().map { it.id }.toSet() }, cards = ImportCardStore(history, { pinStore.pins().map { it.id }.toSet() }) { start + 1000 },
        scenes = ImportSceneStore(db, SceneBuilder(db), { DaoFence(0, epoch, { gen }, { epoch }) }),
        zones = { zoneList },
    )
    private val history = PlaceCardHistory(db, clock = { start + 2000 }, pinActive = { p -> pinStore.pins().any { it.id == p } }, generation = { gen })
    private fun cardRows() = runBlocking { db.placeCardLogDao().cardLogCount() }

    private fun card(cid: String = "c1", place: String = "wd:Q1", kind: String = "PHOTO", at: Long, frameId: String? = "f1", delivery: String = "SENT") =
        """{"id":"$cid","cardId":"cd-$cid","placeId":"$place","title":"Sigiriya","kind":"$kind","delivery":"$delivery","at":$at,"frameId":${frameId?.let { "\u0022$it\u0022" } ?: "null"}}"""
    private fun body(frameId: String = "f1", memId: String = "mem1", withLoc: Boolean = true, markId: String? = "mk:ep1:3", momentId: String = id, st: Long = start, cardsJson: String? = null) = """
        {"items":[{"object_type":"moment","id":"$momentId","startMs":$st,
          "frames":[{"id":"$frameId","ts":${st + 10},"capturedAtMs":${st + 10},"source":"rokid_cxr","motion":"STILL","dwell":false,"blur":0.5,"width":360,"phash":"abcd","hasPhoto":true}],
          "memories":[{"id":"$memId","frameId":"$frameId","text":"a red door","type":"note","tags":"door red","tier":"SHORT","confidence":0.7,"reviewState":"OK","createdAt":${st + 500},"derived":false},
                     {"id":"gone","frameId":"$frameId","text":"stale","reviewState":"INVALIDATED"}],
          "marks":[${if (markId != null) """{"id":"$markId","atMs":${st + 20},"source":"tap"}""" else ""}],
          "placeCards":[${cardsJson ?: card(frameId = frameId, at = st + 600)}],
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
        assertEquals(1, r.frames); assertEquals(1, r.memories); assertEquals(1, r.marks); assertEquals(1, r.locations); assertEquals(1, r.cards)
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

    @Test fun locationInsideAPauseZoneIsNotSaved() = runBlocking<Unit> {
        zoneList = listOf(com.rokid.context.core.model.zone.PauseZone("z", "Home", 12.4, 77.2, 12.6, 77.3))
        val r = run(body())
        assertEquals(0, r.locations); assertEquals(0, db.momentLocationDao().count()); assertEquals(1, r.frames)
    }

    @Test fun locationWithUnreadableZonesIsNotSaved() = runBlocking<Unit> {
        zoneList = null
        val r = run(body())
        assertEquals(0, r.locations); assertEquals(0, db.momentLocationDao().count()); assertEquals(1, r.frames)
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

    // ---- scenes ----
    private val mIds = (0..2).map { MomentId.format(sec + it * 10) }
    private fun momentItem(n: Int) = String(body(frameId = "f$n", memId = "m$n", markId = null, withLoc = false, momentId = mIds[n], st = (sec + n * 10) * 1000, cardsJson = "")).removePrefix("{\"items\":[").removeSuffix("]}")
    private fun sceneJson(sid: String = "S${mIds[0]}", members: List<String> = mIds, derived: String = """[{"field":"SUMMARY","value":"a quiet walk","stale":false},{"field":"CAPTION","value":"stale one","stale":true},{"field":"EMBEDDING","value":null,"stale":false}]""") =
        """{"object_type":"scene","id":"$sid","startMs":${start},"endMs":${start + 30000},"momentIds":[${members.joinToString(",") { "\u0022$it\u0022" }}],"derived":$derived,"clusters":[]}"""
    private fun threeMoments(vararg extra: String) = """{"items":[${(0..2).joinToString(",") { momentItem(it) }}${extra.joinToString("") { ",$it" }}]}""".toByteArray()
    private fun sceneIds() = runBlocking { db.sceneDao().allScenes().map { it.sceneId } }

    @Test fun sceneIsRebuiltFromTheImportedMomentsWithTheSameIdAndImportedText() = runBlocking<Unit> {
        val r = run(threeMoments(sceneJson()))
        assertEquals(listOf("created" to "ok", "created" to "ok", "created" to "ok", "created" to "ok"), r.items)
        assertEquals(listOf("S${mIds[0]}"), sceneIds()); assertEquals(1, r.scenes); assertEquals(0, r.scenesRemapped)
        assertEquals(mIds.toSet(), db.sceneDao().parts("S${mIds[0]}").map { it.refId }.toSet())
        val d = db.objectDao().derivedRow("SCENE", "S${mIds[0]}", "SUMMARY")!!
        assertEquals("a quiet walk", d.value); assertEquals(1, r.sceneTexts)
        assertEquals("imported", db.modelAttributionDao().get(AttributionKind.DERIVED, AttributionKind.derivedRef("SCENE", "S${mIds[0]}", "SUMMARY"))!!.model)
        assertNull("a stale exported text is not imported", db.objectDao().derivedRow("SCENE", "S${mIds[0]}", "CAPTION"))
    }

    @Test fun sceneWithMissingMembersIsRemappedAndGetsNoText() = runBlocking<Unit> {
        val ghost = MomentId.format(sec - 5)
        val r = run(threeMoments(sceneJson(sid = "S$ghost", members = listOf(ghost) + mIds)))
        assertEquals("created" to "remapped", r.items.last()); assertEquals(1, r.scenesRemapped); assertEquals(0, r.sceneTexts)
        assertEquals(listOf("S${mIds[0]}"), sceneIds()); assertNull(db.objectDao().derivedRow("SCENE", "S${mIds[0]}", "SUMMARY"))
    }

    @Test fun sceneWithNoMomentOnThePhoneIsSkipped() {
        assertEquals(listOf("skipped" to "no_members"), run("""{"items":[${sceneJson()}]}""".toByteArray()).items); assertEquals(0, sceneIds().size)
    }

    @Test fun sceneDedupConflictAndDryRun() {
        val dry = run(threeMoments(sceneJson()), dry = true)
        assertEquals("would_create" to "ok", dry.items.last()); assertEquals(0, sceneIds().size)
        run(threeMoments(sceneJson()))
        assertEquals("conflict" to "exists", run("""{"items":[${sceneJson()}]}""".toByteArray()).items.single())
        assertEquals("skipped" to "dedup", run("""{"items":[${sceneJson()}]}""".toByteArray(), dedup = true).items.single())
        assertEquals(1, sceneIds().size)
    }

    @Test fun twoScenesOfTheSameHourDoNotConflictWithEachOthersRebuild() {
        val r = run(threeMoments(sceneJson(), sceneJson(sid = "S${mIds[2]}", members = listOf(mIds[2]))))
        assertTrue("the second item is judged by what existed before the request: $r", r.items.last().first != "conflict")
    }

    @Test fun badSceneItemsAre400() {
        fun bad(j: String) { try { DumpImportParser.parse("""{"items":[$j]}""".toByteArray()); fail(j) } catch (e: DumpImportParser.Bad) { assertEquals(0, e.index) } }
        bad(sceneJson(sid = "x")); bad(sceneJson(members = emptyList())); bad(sceneJson(members = listOf("zz"))); bad(sceneJson(members = listOf(mIds[0], mIds[0])))
        bad(sceneJson().replace("\"momentIds\"", "\"nope\"")); bad(sceneJson(derived = "[{\"value\":\"x\"}]")); bad(sceneJson(derived = "\"x\""))
    }

    @Test fun sceneForgetAndDeleteAllLeaveZeroRows() = runBlocking<Unit> {
        run(threeMoments(sceneJson()))
        val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 99L }
        MomentForget(db, svc) { 99L }.forgetMoments(mIds)
        assertEquals(0, sceneIds().size); assertEquals(0, db.sceneDao().partCount()); assertNull(db.objectDao().derivedRow("SCENE", "S${mIds[0]}", "SUMMARY"))
        val other = (3..5).map { MomentId.format(sec + 600 + it * 10) }
        val items = other.mapIndexed { n, m -> String(body(frameId = "g$n", memId = "h$n", markId = null, withLoc = false, momentId = m, st = (sec + 600 + (n + 3) * 10) * 1000, cardsJson = "")).removePrefix("{\"items\":[").removeSuffix("]}") }
        run("""{"items":[${items.joinToString(",")},${sceneJson(sid = "S${other[0]}", members = other)}]}""".toByteArray())
        assertEquals(1, sceneIds().size)
        db.wipeDao().wipeMemory(99L)
        assertEquals(0, sceneIds().size); assertEquals(0, db.sceneDao().partCount()); assertEquals(0, db.modelAttributionDao().count())
    }

    // ---- pins ----
    private val pinA = "user:00000000-0000-0000-0000-00000000000a"
    private val pinB = "user:00000000-0000-0000-0000-00000000000b"
    private fun pinJson(pid: String = pinA, moment: String? = null, kind: String = "monument", lat: Double = 7.957, r: Int = 60, parent: String? = null) =
        """{"object_type":"pin","id":"$pid","name":"Mirror Wall","kind":"$kind","lat":$lat,"lon":80.759,"radiusM":$r,"parentId":${parent?.let { "\u0022$it\u0022" } ?: "null"},"note":"old graffiti","momentId":${moment?.let { "\u0022$it\u0022" } ?: "null"},"pinnedAt":1700000000123}"""
    private fun pins(vararg j: String) = """{"items":[${j.joinToString(",")}]}""".toByteArray()

    @Test fun pinKeepsOriginalIdTimePositionAndNoteAndIsNotAChildOfAMoment() {
        val r = run(pins(pinJson()))
        assertEquals(listOf("created" to "ok"), r.items); assertEquals(1, r.pins)
        val p = pinStore.pins().single()
        assertEquals(pinA, p.id); assertEquals(1700000000123L, p.pinnedAt); assertEquals(7.957, p.lat, 0.0); assertEquals("old graffiti", p.note); assertNull(p.momentId)
    }

    @Test fun pinKeepsItsReferenceMomentWhenTheMomentIsImportedInTheSameRequestElseDropsTheLink() {
        val both = String(body()).trimEnd().removeSuffix("]}")
        val r = run((both + "," + pinJson(moment = id) + "," + pinJson(pinB, moment = "00000001") + "]}").toByteArray())
        assertEquals(listOf("created" to "ok", "created" to "ok", "created" to "link_dropped"), r.items); assertEquals(1, r.pinLinksDropped)
        assertEquals(id, pinStore.pins().first { it.id == pinA }.momentId); assertNull(pinStore.pins().first { it.id == pinB }.momentId)
    }

    @Test fun pinDedupConflictDryRunAndZone() {
        assertEquals(listOf("would_create" to "ok"), run(pins(pinJson()), dry = true).items); assertEquals(0, pinStore.pins().size)
        zoneOn = true
        assertEquals(listOf("skipped" to "zone"), run(pins(pinJson())).items); assertEquals(0, pinStore.pins().size)
        zoneOn = false
        run(pins(pinJson()))
        assertEquals(listOf("conflict" to "exists"), run(pins(pinJson())).items)
        assertEquals(listOf("skipped" to "dedup"), run(pins(pinJson()), dedup = true).items)
        assertEquals(1, pinStore.pins().size)
    }

    @Test fun badPinsAre400() {
        fun bad(j: String) { try { DumpImportParser.parse(pins(j)); fail(j) } catch (e: DumpImportParser.Bad) { assertEquals(0, e.index) } }
        bad(pinJson(pid = "wd:Q1")); bad(pinJson(kind = "person")); bad(pinJson(lat = 91.0)); bad(pinJson(r = 5)); bad(pinJson(moment = "zz")); bad(pinJson(parent = "user:00000000-0000-0000-0000-00000000000c"))
        bad(pinJson().replace("\"lon\":80.759,", "")); bad(pinJson().replace("\"name\":\"Mirror Wall\"", "\"name\":\"  \""))
    }

    @Test fun forgetOfTheReferenceMomentAndDeleteAllRemoveTheLinkedImportedPin() = runBlocking<Unit> {
        val both = String(body()).trimEnd().removeSuffix("]}")
        run((both + "," + pinJson(moment = id) + "," + pinJson(pinB) + "]}").toByteArray())
        assertEquals(2, pinStore.pins().size)
        val alive: suspend (List<String>) -> Set<String> = { l -> db.momentDao().momentsAliveOrExpired(l) }
        val save: suspend (List<UserPlace>) -> Boolean = { pinStore.write(it) }
        val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 99L }
        PinMomentLinks.explicitStore(pinStore, alive, save, { it.toSet() }, { true }, {}, { MomentForget(db, svc) { 99L }.forgetMoments(listOf(id)) })
        assertEquals("Forget of the Moment deletes the pin that referenced it; the unlinked pin is user configuration", listOf(pinB), pinStore.pins().map { it.id })
        // Delete all: a linked pin goes with it, an unlinked pin is kept by the owner's rule (pins are user configuration)
        run(String(body(frameId = "f2", memId = "mem2", markId = null, momentId = MomentId.format(sec + 50), st = (sec + 50) * 1000)).let { (it.trimEnd().removeSuffix("]}") + "," + pinJson(moment = MomentId.format(sec + 50)) + "]}") }.toByteArray())
        assertNotNull(pinStore.pins().first { it.id == pinA }.momentId)
        val op = PinMomentLinks.beginWipeStore(pinStore, alive, save, resume = false)
        PinMomentLinks.wipeCommittedStore(pinStore, op); db.wipeDao().wipeMemory(99L)
        PlacePinning.underPinLock { PinMomentLinks.resolveStore(pinStore, alive, save, op) }; PinMomentLinks.endWipe(op)
        assertTrue(pinStore.pins().none { it.momentId != null }); assertEquals(listOf(pinB), pinStore.pins().map { it.id })
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

    // ---- place cards ----
    @Test fun cardKeepsOriginalIdTimeDeliveryAndNoLinkForAPackPlace() = runBlocking<Unit> {
        run(body())
        val c = db.placeCardLogDao().cardLog("c1")!!
        assertEquals(start + 600, c.at); assertEquals("SENT", c.delivery); assertEquals("wd:Q1", c.placeId); assertEquals("f1", c.frameId); assertEquals("cd-c1", c.cardId)
        assertEquals("a pack place gets no place_link", 0, db.placeLinkDao().momentIdsOf("wd:Q1").size)
    }

    @Test fun cardOfAUserPinMakesItsPlaceLinkThroughTheHistoryWriterOnly() = runBlocking<Unit> {
        val moment = String(body(cardsJson = card(place = pinA, at = start + 600))).trimEnd().removeSuffix("]}")
        val r = run((moment + "," + pinJson() + "]}").toByteArray())
        assertEquals(1, r.cards); assertEquals(listOf(id), db.placeLinkDao().momentIdsOf(pinA))
    }

    @Test fun cardSkipTokensAndDuplicateIdAcrossMoments() = runBlocking<Unit> {
        val cards = listOf(card("c1", at = start + 600), card("c2", place = pinB, at = start + 600), card("c3", at = 5), card("c4", frameId = "nope", at = start + 600), card("c5", kind = "PIN_CLUSTER", at = start + 600)).joinToString(",")
        val r = run(body(cardsJson = cards))
        assertEquals(1, r.cards)
        assertEquals(mapOf("pin_missing" to 1, "expired" to 1, "frame_missing" to 1, "no_members" to 1), r.cardsSkipped)
        // the same card id on another Moment is `exists`, never a second row
        val second = MomentId.format(sec + 7)
        val r2 = run(body(frameId = "f9", memId = "m9", markId = null, momentId = second, st = (sec + 7) * 1000, cardsJson = card("c1", at = (sec + 7) * 1000 + 600, frameId = "f9")))
        assertEquals(listOf("created" to "ok"), r2.items); assertEquals(0, r2.cards); assertEquals(mapOf("exists" to 1), r2.cardsSkipped); assertEquals(1, cardRows())
    }

    @Test fun cardDryRunWritesNoRowAndAMomentConflictWritesNone() {
        val r = run(body(), dry = true)
        assertEquals(1, r.cards); assertEquals(0, cardRows())
        run(body()); assertEquals(1, cardRows())
        run(body(), dedup = true); run(body()); assertEquals(1, cardRows())
    }

    @Test fun badCardsAre400() {
        fun bad(c: String) { try { DumpImportParser.parse(body(cardsJson = c)); fail(c) } catch (e: DumpImportParser.Bad) { assertEquals(0, e.index) } }
        bad(card(kind = "WEIRD", at = start)); bad(card(at = -1)); bad(card(delivery = "NOPE", at = start)); bad(card(place = "bad id!", at = start)); bad(card(frameId = "../x", at = start))
        bad(card("c1", at = start) + "," + card("c1", at = start)); bad(card("bad id", at = start))
    }

    @Test fun cardsLeaveZeroRowsAfterForgetAndAfterDeleteAll() = runBlocking<Unit> {
        val moment = String(body(cardsJson = card(place = pinA, at = start + 600))).trimEnd().removeSuffix("]}")
        run((moment + "," + pinJson() + "]}").toByteArray()); assertEquals(1, cardRows())
        val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 99L }
        MomentForget(db, svc) { 99L }.forgetMoments(listOf(id))
        assertEquals(0, cardRows()); assertEquals(0, db.placeLinkDao().momentIdsOf(pinA).size)
        val s2 = MomentId.format(sec + 50)
        run(body(frameId = "f2", memId = "mem2", markId = null, momentId = s2, st = (sec + 50) * 1000, cardsJson = card("c9", place = pinA, at = (sec + 50) * 1000 + 600, frameId = "f2")))
        assertEquals(1, cardRows()); assertEquals(1, db.placeLinkDao().momentIdsOf(pinA).size)
        db.wipeDao().wipeMemory(99L)
        assertEquals(0, cardRows()); assertEquals(0, db.placeLinkDao().momentIdsOf(pinA).size)
    }
}
