package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.ContextDatabase
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.forget.ForgetService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * CH 10 cases 1-5 (v18 WP3): the insert is one guarded `context.db` transaction, so a Forget and an insert can never interleave. Real in-memory Room, the real [ForgetService]. Synthetic names only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoryInsertVsForgetTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db: ContextDatabase = DatabaseFactory.inMemory(ctx)
    private var gen = 0L
    private var epoch = 0L
    private val removing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val active = mutableSetOf<String>()
    private val logs = mutableListOf<String>()
    private var seq = 0
    private val history = PlaceCardHistory(db, clock = { 5_000L }, log = { logs += it }, newId = { "h${++seq}" }, pinActive = { it in active }, removing = removing, generation = { gen }, fenceEpoch = { epoch })
    private val svc = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
    private val mf = com.rokid.context.memory.forget.MomentForget(db, svc) { 1_000 }
    private val pin = "user:00000000-0000-0000-0000-000000000001"

    @After fun close() { db.close() }

    private suspend fun frames() = listOf("f1", "f2").forEach { db.frameDao().insertIgnore(FrameEntity(it, 1, "/$it.jpg", "rokid_glasses")) }
    private suspend fun ev(auth: HistoryAuthority, frame: String? = null, place: String = "wd:Q1") = PlaceHistoryEvent(HistoryKind.PHOTO, place, "Site A", frameId = frame, authority = auth)
    private fun reasons() = logs.filter { it.startsWith("place history add refused") }.map { it.substringAfter("reason=") }

    @Test fun case1_aPhotoForgetBetweenCaptureAndInsertRefusesTheRow() = runBlocking {
        frames(); val a = history.capture()
        svc.forgetMoment("f2")
        assertNull(history.add(ev(a, "f1"))); assertEquals(listOf("rev"), reasons()); assertEquals(0, db.placeCardLogDao().cardLogCount())
    }

    @Test fun case2_anInsertThatCommittedFirstIsDeletedByTheForget() = runBlocking {
        frames(); val a = history.capture()
        assertNotNull(history.add(ev(a, "f1"))); assertEquals(1, db.placeCardLogDao().cardLogCount())
        svc.forgetMoment("f1")
        assertEquals(0, db.placeCardLogDao().cardLogCount())
    }

    @Test fun case3_deleteAllBetweenCaptureAndInsertIsRefused() = runBlocking {
        val a = history.capture(); gen++ // MemoryWipe.generation moves
        assertNull(history.add(ev(a))); assertEquals(listOf("gen"), reasons())
        val b = history.capture(); db.wipeDao().wipeMemory(2_000)
        assertNull(history.add(ev(b))); assertEquals("the durable rev moved too", "rev", reasons().last())
    }

    @Test fun case4_aRangeForgetIsRefusedAndDeletesTheRowsOfItsSpan() = runBlocking {
        val a = history.capture(); assertNotNull(history.add(ev(a))) // row at 5_000
        val b = history.capture()
        mf.forgetRange(4_000, 6_000)
        assertEquals("the row of the span went", 0, db.placeCardLogDao().cardLogCount())
        assertNull(history.add(ev(b))); assertEquals("rev", reasons().last())
    }

    @Test fun fenceEpochMovedIsRefused() = runBlocking {
        val a = history.capture(); epoch++
        assertNull(history.add(ev(a))); assertEquals(listOf("fence"), reasons())
    }

    @Test fun case5_pinRemoval() = runBlocking {
        active += pin
        val a = history.capture()
        assertNotNull(history.add(ev(a, place = pin)))
        removing += pin
        assertNull("during the removal the insert is refused", history.add(ev(a, place = pin))); assertEquals("pin", reasons().last())
        history.deletePins(listOf(pin)); active -= pin; removing -= pin
        assertEquals("the row inserted before the removal is deleted", 0, db.placeCardLogDao().cardLogCount())
        assertNull("a removed pin is refused afterwards", history.add(ev(a, place = pin)))
    }

    @Test fun anExpiredPhotoIsWrittenWithPhotoGoneAndAForgottenOneIsRefused() = runBlocking {
        frames(); val a = history.capture()
        svc.expirePhotos(listOf("f1")); svc.forgetMoment("f2")
        val after = history.capture()
        assertNotNull(history.add(ev(after, "f1"))); assertEquals(1, db.placeCardLogDao().recentCardLog(5).single().photoGone)
        assertNull(history.add(ev(after, "f2"))); assertEquals("frame", reasons().last())
        assertNull("the old authority is stale after the Forget", history.add(ev(a, "f1")))
    }

    @Test fun twoHundredInterleavingsKeepTheInvariant() = runBlocking {
        val rnd = java.util.Random(7); frames()
        repeat(200) {
            val a = history.capture()
            var forgot = false
            when (rnd.nextInt(3)) { 0 -> { mf.forgetRange(4_000, 6_000); forgot = true }; 1 -> { svc.forgetMoment(if (rnd.nextBoolean()) "f1" else "f2"); forgot = true }; else -> Unit }
            val id = history.add(ev(a, if (rnd.nextBoolean()) "f1" else null))
            if (forgot) assertNull("a row under a stale authority is never written", id)
            val rows = db.placeCardLogDao().recentCardLog(500)
            assertTrue("no live row of a tombstoned frame", rows.none { r -> r.frameId != null && r.photoGone == 0 && db.canvasDao().tombstonedAmong(listOf(r.frameId!!)) > 0 })
        }
    }
}
