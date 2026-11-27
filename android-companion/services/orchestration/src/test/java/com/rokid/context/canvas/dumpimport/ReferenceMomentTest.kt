package com.rokid.context.canvas.dumpimport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.memory.FrameIntake
import com.rokid.context.memory.FrameRows
import com.rokid.context.memory.MemoryRepository
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.AttributionKind
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.FrameSourceEntity
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.ModelAttribution
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.place.UserPinStore
import com.rokid.context.work.frames.place.ManualPlace
import com.rokid.context.work.frames.place.ManualResult
import com.rokid.context.work.frames.place.PinMomentLinks
import com.rokid.context.work.frames.place.PlacePinning
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Developer reference photo's Moment: the same writers as the import, time now, no location row, no half state, and the pin link. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReferenceMomentTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val rawPrefs get() = ctx.getSharedPreferences(UserPinStore.PREFS, android.content.Context.MODE_PRIVATE)
    private val pinStore get() = UserPinStore(rawPrefs)
    @Before fun clearPins() { rawPrefs.edit().clear().commit() }

    private val now = 1_800_000_123_456L
    private val sec = Math.floorDiv(now, 1000L)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val intake = FrameIntake(db) { 42L }
    private var frameOk = true
    private var memoryOk = true
    private var gen = 0L
    private var discards = 0
    private val frames = ImportFrameWriter { f ->
        if (!frameOk) return@ImportFrameWriter false
        val rows = FrameRows(
            FrameMetaEntity(f.id, f.id, null, f.motion, f.dwell, f.blur, f.dhash, f.phash, f.width, null, null, null, null, null, 42L, routedAt = 42L, capturedAtMs = f.capturedAtMs, markRef = f.markRef),
            FrameStateEntity(f.id, "EXTRACTED", 0, null, null, null, 42L),
        )
        intake.commit(FrameEntity(f.id, f.capturedAtMs, "/ref/${f.id}.jpg", f.source, "", placeLabel = f.placeLabel), rows) == FrameIntake.Outcome.ADDED
    }
    private val memories = ImportMemoryWriter { m ->
        if (!memoryOk) return@ImportMemoryWriter false
        MemoryRepository(db.memoryDao()).addIgnore(MemoryEntity(m.id, m.frameId, m.text, m.type, m.tags.joinToString(" "), m.tier, m.confidence, m.review, false, m.createdAt, m.derived, ""))
            .also { db.modelAttributionDao().record(AttributionKind.MEMORY, m.id, ModelAttribution("imported"), 42L); db.frameSourceDao().insertAll(listOf(FrameSourceEntity(m.id, m.frameId, null, "FACT"))) }
    }
    private val svc get() = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 99L }
    private var ids = 0
    private fun maker() = ReferenceMoment(db, frames, memories, { l -> MomentBuilder(db).build(l) }, { gen }, { fid -> discards++; svc.forgetMoment(fid); true }, { now }, { "00000000-0000-0000-0000-%012d".format(++ids) })
    private val pinning = PlacePinning(
        fix = { null }, zoneBlocked = { false }, zones = { emptyList() }, nearby = { _, _ -> emptyList() }, load = { pinStore.pins() }, save = { pinStore.write(it) },
        elapsed = { 0L }, newId = { "user:00000000-0000-0000-0000-%012d".format(++ids) }, momentAlive = { m -> db.momentDao().photoMomentsOf(listOf(m)).isNotEmpty() },
    )
    private fun momentRows() = runBlocking { db.momentDao().momentIdsBetween(MomentId.format(0), MomentId.format(MomentId.MAX_SECOND)) }

    private suspend fun pin(): String = (ManualPlace.create(pinning, "Office", "Main campus building", "attraction", "12.9716, 77.5946", "") as ManualResult.Saved).place.id

    @Test fun makesOneMomentAtNowWithTheImportWritersAndLinksThePin() = runBlocking<Unit> {
        val pid = pin()
        val made = maker().create(jpeg, "Main campus building")!!
        assertEquals(MomentId.format(sec), made.momentId)
        assertTrue(pinning.setMoment(pid, made.momentId))
        assertEquals(made.momentId, pinStore.pins().single().momentId)
        assertEquals("exactly one Moment", listOf(made.momentId), momentRows())
        val f = db.frameDao().all().single()
        assertEquals("rokid_reference", f.source); assertEquals(now, f.ts); assertEquals(made.frameId, f.id)
        assertEquals("EXTRACTED, no description or summary model call can run", "EXTRACTED", db.frameStateDao().get(f.id)?.state)
        val mem = db.memoryDao().all().single()
        assertEquals("Main campus building", mem.text); assertEquals(f.id, mem.frameId)
        assertEquals("imported", db.modelAttributionDao().get(AttributionKind.MEMORY, mem.id)?.model)
        assertEquals("no location row", 0, db.momentLocationDao().count())
        assertTrue("the live location recorder never takes it as a candidate", db.momentLocationDao().framesSince(0).isEmpty())
        assertEquals(1, db.momentDao().photoMomentsOf(listOf(made.momentId)).size)
    }

    @Test fun noDescriptionMeansNoMemory() = runBlocking<Unit> {
        assertNotNull(maker().create(jpeg, "   "))
        assertEquals(0, db.memoryDao().all().size); assertEquals(1, db.frameDao().all().size)
    }

    @Test fun aFailedFrameWriteLeavesNothingAndNoOrphanMoment() = runBlocking<Unit> {
        frameOk = false
        assertNull(maker().create(jpeg, "x"))
        assertEquals(0, db.frameDao().all().size); assertEquals(0, db.memoryDao().all().size); assertTrue(momentRows().isEmpty()); assertEquals(0, discards)
    }

    @Test fun aFailedMemoryWriteRemovesThePhotoAgainAndNoOrphanMomentIsLeft() = runBlocking<Unit> {
        memoryOk = false
        assertNull(maker().create(jpeg, "x"))
        assertEquals(1, discards)
        assertEquals(0, db.frameDao().all().size); assertEquals(0, db.memoryDao().all().size)
        assertTrue(db.momentDao().photoMomentsOf(listOf(MomentId.format(sec))).isEmpty()); assertTrue(momentRows().isEmpty())
        assertEquals(0, db.momentLocationDao().count())
    }

    @Test fun aDeleteAllDuringTheWriteDiscardsIt() = runBlocking<Unit> {
        val m = ReferenceMoment(db, frames, { mm -> gen++; memories.store(mm) }, { l -> MomentBuilder(db).build(l) }, { gen }, { fid -> discards++; svc.forgetMoment(fid); true }, { now })
        assertNull(m.create(jpeg, "x")); assertEquals(1, discards); assertTrue(momentRows().isEmpty()); assertEquals(0, db.frameDao().all().size)
    }

    @Test fun aSecondAlreadyUsedMovesOnToTheNext() = runBlocking<Unit> {
        val first = maker().create(jpeg, "a")!!
        val second = maker().create(jpeg, "b")!!
        assertEquals(MomentId.format(sec), first.momentId); assertEquals(MomentId.format(sec + 1), second.momentId)
        assertEquals(2, momentRows().size)
    }

    @Test fun aForgottenSecondIsNeverRecreated() = runBlocking<Unit> {
        val first = maker().create(jpeg, "a")!!
        svc.forgetMoment(first.frameId)
        db.momentDao().let { runBlocking { com.rokid.context.memory.forget.MomentForget(db, svc) { 99L }.forgetMoments(listOf(first.momentId)) } }
        val again = maker().create(jpeg, "b")!!
        assertEquals("the forgotten second is skipped", MomentId.format(sec + 1), again.momentId)
    }

    @Test fun forgetOfTheReferenceMomentDeletesThePinThatPointsAtItPerThePinRules() = runBlocking<Unit> {
        val pid = pin(); val other = pin2()
        val made = maker().create(jpeg, "x")!!
        assertTrue(pinning.setMoment(pid, made.momentId))
        val alive: suspend (List<String>) -> Set<String> = { l -> db.momentDao().momentsAliveOrExpired(l) }
        val save: suspend (List<com.rokid.context.memory.place.UserPlace>) -> Boolean = { pinStore.write(it) }
        PinMomentLinks.explicitStore(pinStore, alive, save, { it.toSet() }, { true }, {}, { com.rokid.context.memory.forget.MomentForget(db, svc) { 99L }.forgetMoments(listOf(made.momentId)) })
        assertEquals("the pin that referenced the forgotten Moment goes, the unlinked pin stays", listOf(other), pinStore.pins().map { it.id })
        assertEquals(0, db.frameDao().all().size); assertEquals(0, db.memoryDao().all().size); assertEquals(0, db.momentLocationDao().count())
    }

    private suspend fun pin2(): String = (ManualPlace.create(pinning, "Home", "", "attraction", "13.0000, 77.0000", "") as ManualResult.Saved).place.id
}
