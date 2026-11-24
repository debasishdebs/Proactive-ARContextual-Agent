package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.memory.place.UserPlace
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Place card history (spec 2026-11-24 B1): the store, its pruning, and the Forget wiring through the real [ForgetListeners]. Synthetic names only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceCardHistoryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = PlacesDatabase.inMemory(ctx)
    private val pack = PlacePack(db, null)
    private var now = 1_000_000_000_000L
    private val logs = mutableListOf<String>()
    private var on = true
    private var seq = 0
    private val history = PlaceCardHistory(pack.cardLogDao, clock = { now }, log = { logs += it }, enabled = { on }, newId = { "h${++seq}" })

    @Before fun wire() {
        ctx.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        PlaceGraph.installForTests(pack); PlaceGraph.installHistoryForTests(history); PlaceGraph.attach(ctx); PlaceGraph.registerListeners()
    }
    @After fun close() { PlaceGraph.installForTests(null); db.close() }

    private fun ev(kind: HistoryKind = HistoryKind.PHOTO, place: String = "wd:Q1", frame: String? = null, members: List<String> = emptyList(), card: String? = null) =
        PlaceHistoryEvent(kind, place, "Site A", members, frame, card)

    private fun pin(n: Int) = UserPlace("user:00000000-0000-0000-0000-%012d".format(n), "Room A$n", PlaceKinds.ARCHAEOLOGICAL_SITE, 7.957, 80.759, 20, null, "")

    @Test fun everyCommittedWriteBumpsTheChangeCounterSoUisNeverPoll() = runBlocking {
        var n = PlaceCardHistory.changes.value
        fun assertBumped(what: String) { val v = PlaceCardHistory.changes.value; assertTrue(what, v > n); n = v }
        val id = history.add(ev(card = "c1", frame = "f1"))!!; assertBumped("add")
        history.markDelivery("c1", CardDelivery.SENT); assertBumped("delivery")
        history.markDelivery("nope", CardDelivery.SENT); assertEquals("a failed update is no change", n, PlaceCardHistory.changes.value)
        history.onFramesForgotten(listOf("f1")); assertBumped("forget")
        history.add(ev()); n = PlaceCardHistory.changes.value
        history.delete(history.recent().first().id); assertBumped("delete")
        history.add(ev()); n = PlaceCardHistory.changes.value
        history.clearAll(); assertBumped("clear")
    }

    @Test fun addStoresRowsNewestFirstWithDerivedDelivery() = runBlocking {
        history.add(ev(card = "c1")); now += 1000; history.add(ev(HistoryKind.POSSIBLE).copy(confPct = 54))
        val rows = history.recent()
        assertEquals(listOf("POSSIBLE", "PHOTO"), rows.map { it.kind })
        assertEquals(CardDelivery.NOT_SENT_POSSIBLE, rows[0].delivery); assertEquals(54, rows[0].confPct)
        assertEquals(CardDelivery.PENDING, rows[1].delivery)
        assertTrue(logs.first().startsWith("place history ready rows=")); assertTrue(logs.any { it == "place history add kind=PHOTO delivery=PENDING rows=1" })
    }

    @Test fun history_add_prune90d_cap500() = runBlocking {
        history.add(ev()) // the old one
        now += PlaceCardHistory.RETAIN_MS + 1
        (1..505).forEach { history.add(ev(place = "wd:Q$it")); now += 1 }
        assertEquals("the 90 day row went at the first add (the day prune); the cap is enforced by the next prune", 505, pack.cardLogDao.cardLogCount())
        assertEquals(5, history.prune())
        assertEquals(500, pack.cardLogDao.cardLogCount())
        assertEquals("the newest rows stay", "wd:Q505", history.recent().first().placeId)
        assertTrue(history.recent().none { it.placeId == "wd:Q1" })
        assertTrue(logs.contains("place history prune removed=5"))
    }

    @Test fun offStopsNewRowsButNeverBlocks() = runBlocking {
        on = false
        assertNull(history.add(ev())); assertEquals(0, pack.cardLogDao.cardLogCount())
    }

    @Test fun userForget_deletesRow() = runBlocking {
        history.add(ev(frame = "f1")); history.add(ev(frame = "f2"))
        ForgetListeners.framesForgotten(listOf("f1"))
        assertEquals(listOf("f2"), history.recent().map { it.frameId })
        assertTrue(logs.contains("place history forget frames=1 removed=1"))
    }

    @Test fun photoExpiry_marksGoneKeepsRowAndItsLineage() = runBlocking {
        history.add(ev(frame = "f1"))
        ForgetListeners.photosExpired(listOf("f1"))
        val r = history.recent().single()
        assertEquals("retention v3 (r2-5): the frame id stays as private lineage", "f1", r.frameId); assertEquals(1, r.photoGone)
        assertTrue(logs.contains("place history expire frames=1 marked=1"))
        ForgetListeners.framesForgotten(listOf("f1")) // a later user Forget of the expired photo still deletes the row
        assertTrue(history.recent().isEmpty())
    }

    /** End to end through the real memory ForgetService: expiry only marks the photo gone (row and its lineage stay), a user Forget deletes the row, also for the expired photo. */
    @Test fun realForgetService_expiryKeepsRowAndLineage_userForgetDeletesRow() = runBlocking {
        val mdb = com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx)
        try {
            val svc = com.rokid.context.memory.forget.ForgetService(mdb, com.rokid.context.memory.ProposalRepository(mdb.proposalDao(), mdb.actionDao(), mdb)) { 1_000 }
            listOf("f1", "f2").forEach { mdb.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity(it, 1, "/$it.jpg", "rokid_glasses")) }
            history.add(ev(frame = "f1")); history.add(ev(frame = "f2"))
            svc.expirePhotos(listOf("f1"))
            val rows = history.recent()
            assertEquals("expiry keeps both rows", 2, rows.size)
            val gone = rows.single { it.photoGone == 1 }
            assertEquals("the frame id stays as private lineage (r2-5)", "f1", gone.frameId); assertEquals("f2", rows.single { it.photoGone == 0 }.frameId)
            svc.forgetMoment("f2")
            assertEquals("a user Forget deletes the row", 1, history.recent().size)
            assertEquals(1, history.recent().single().photoGone)
            svc.forgetMoment("f1")
            assertTrue("a user Forget of the EXPIRED photo deletes its row too", history.recent().isEmpty())
        } finally { mdb.close() }
    }

    @Test fun retentionRangeNeverDeletesRows_userRangeDoes() = runBlocking {
        history.add(ev()); val at = now
        ForgetListeners.rangeExpired(at - 10, at + 10, emptyList())
        assertEquals("the time link of a retention expiry keeps the row", 1, history.recent().size)
        ForgetListeners.rangeForgotten(at + 1, at + 10, emptyList())
        assertEquals("a range that misses the row keeps it", 1, history.recent().size)
        ForgetListeners.rangeForgotten(at - 10, at + 10, emptyList())
        assertTrue(history.recent().isEmpty()); assertTrue(logs.contains("place history range removed=1"))
    }

    @Test fun deleteAll_clears() = runBlocking {
        history.add(ev()); history.add(ev(HistoryKind.POSSIBLE))
        ForgetListeners.deleteAll()
        assertTrue(history.recent().isEmpty()); assertTrue(logs.contains("place history cleared reason=delete_all removed=2"))
    }

    @Test fun deleteAllThroughThePackAlsoClears() = runBlocking {
        history.add(ev())
        PlaceGraph.deleteAll(ctx)
        assertTrue(history.recent().isEmpty())
    }

    @Test fun pinDeleted_deletesItsRows() = runBlocking {
        val p1 = pin(1); val p2 = pin(2); val p3 = pin(3)
        assertTrue(PlaceGraph.savePins(ctx, listOf(p1, p2, p3)))
        history.add(ev(HistoryKind.PIN, place = p1.id)); history.add(ev(HistoryKind.PIN_CLUSTER, place = p2.id, members = listOf(p1.id, p2.id)))
        history.add(ev(HistoryKind.PIN, place = p3.id)); history.add(ev(HistoryKind.PHOTO, place = "wd:Q1"))
        assertTrue(PlaceGraph.savePins(ctx, listOf(p2, p3))) // p1 removed: its row and the cluster that lists it go
        assertEquals(setOf(p3.id, "wd:Q1"), history.recent().map { it.placeId }.toSet())
        assertTrue(logs.contains("place history pin removed=2"))
        assertTrue(PlaceGraph.savePins(ctx, listOf(p2, p3))) // an unchanged list removes nothing
        assertEquals(2, history.recent().size)
    }

    @Test fun deliveryMarks() = runBlocking {
        history.add(ev(card = "c1")); history.add(ev(card = "c2"))
        assertTrue(history.markDelivery("c1", CardDelivery.SENT))
        assertFalse(history.markIfPending("c1", CardDelivery.NOT_SENT_LINK))
        assertTrue(history.markIfPending("c2", CardDelivery.NOT_SENT_LINK))
        assertEquals(setOf(CardDelivery.SENT, CardDelivery.NOT_SENT_LINK), history.recent().map { it.delivery }.toSet())
        assertFalse(history.markDelivery("nope", CardDelivery.SENT))
    }

    @Test fun aDeliveryReportedBeforeTheRowExistsIsAppliedWhenTheRowIsAdded() = runBlocking {
        assertFalse(history.markDelivery("c9", CardDelivery.SENT))
        assertFalse(history.markIfPending("c8", CardDelivery.NOT_SENT_LINK))
        history.add(ev(card = "c9")); history.add(ev(card = "c8")); history.add(ev(card = "c7"))
        val by = history.recent().associate { it.cardId to it.delivery }
        assertEquals(CardDelivery.SENT, by["c9"]); assertEquals(CardDelivery.NOT_SENT_LINK, by["c8"]); assertEquals(CardDelivery.PENDING, by["c7"])
    }

    @Test fun rowsHoldNoPositionAndLogsHoldNoContent() = runBlocking {
        history.add(PlaceHistoryEvent(HistoryKind.PIN, "user:00000000-0000-0000-0000-000000000001", "Room A1", frameId = "frame-1", cardId = "card-1"))
        ForgetListeners.framesForgotten(listOf("frame-1")); ForgetListeners.deleteAll()
        logs.forEach { l -> assertFalse(l, l.contains("Room A1")); assertFalse(l, l.contains("frame-1")); assertFalse(l, l.contains("user:")); assertFalse(l, Regex("\\d{5,}").containsMatchIn(l)) }
        assertNotNull(logs.firstOrNull())
    }

    @Test fun aFailureIsSwallowedAndLogged() = runBlocking {
        val broken = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(com.rokid.context.memory.place.PlacesDao::class.java)) { _, _, _ -> throw IllegalStateException("closed") } as com.rokid.context.memory.place.PlacesDao
        val h = PlaceCardHistory(broken, clock = { now }, log = { logs += it })
        assertNull(h.add(ev())); assertTrue(h.recent().isEmpty()); assertFalse(h.markDelivery("c", CardDelivery.SENT))
        assertTrue(logs.contains("place history add failed")); assertTrue(logs.contains("place history read failed"))
        logs.forEach { l -> assertFalse(l, l.contains("closed")) } // the exception text is never logged
    }

    // ---- Codex place fixes r1: P1-4 / P1-5 ----

    /** Fails the deletions while [fail] is set; everything else is the real DAO. */
    private class FlakyDao(private val real: com.rokid.context.memory.place.PlacesDao) : com.rokid.context.memory.place.PlacesDao by real {
        @Volatile var fail = true
        override suspend fun deleteCardLogBetween(from: Long, to: Long): Int { if (fail) throw IllegalStateException("db"); return real.deleteCardLogBetween(from, to) }
        override suspend fun deleteCardLogForFrames(frameIds: List<String>): Int { if (fail) throw IllegalStateException("db"); return real.deleteCardLogForFrames(frameIds) }
        override suspend fun deleteCardLogForPin(pinId: String): Int { if (fail) throw IllegalStateException("db"); return real.deleteCardLogForPin(pinId) }
        override suspend fun deleteAllCardLog(): Int { if (fail) throw IllegalStateException("db"); return real.deleteAllCardLog() }
    }

    private fun failing(owed: HistoryOwed) = FlakyDao(pack.cardLogDao).let { it to PlaceCardHistory(it, clock = { now }, log = { logs += it }, newId = { "h${++seq}" }, owed = owed) }

    @Test fun deleteAllFailure_propagates_staysOwed_andIsRetriedAtStart() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        h.add(ev()); h.add(ev(HistoryKind.POSSIBLE))
        var thrown = false
        try { h.clearAll() } catch (_: HistoryDeletionPending) { thrown = true }
        assertTrue("a failed deletion is not swallowed", thrown); assertTrue(h.hasOwed()); assertEquals("the private rows are still there", 2, pack.cardLogDao.cardLogCount())
        dao.fail = false; now += 5_000; h.add(ev(place = "wd:Q7")) // a row made AFTER the Delete all must survive the retry
        assertTrue(PlaceCardHistory(dao, clock = { now }, owed = owed).retryOwed())
        assertEquals(listOf("wd:Q7"), pack.cardLogDao.recentCardLog(10).map { it.placeId }); assertFalse(owed.all().isNotEmpty())
    }

    @Test fun forgetRangeFramesAndPinFailures_allStayOwed_untilRetried() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        h.add(ev(frame = "f1")); h.add(ev(HistoryKind.PIN, place = pin(1).id)); h.add(ev())
        for (op in listOf<suspend () -> Unit>({ h.onFramesForgotten(listOf("f1")) }, { h.onRangeForgotten(0, now + 1) }, { h.onPinRemoved(listOf(pin(1).id)) })) {
            var thrown = false; try { op() } catch (_: HistoryDeletionPending) { thrown = true }; assertTrue(thrown)
        }
        assertEquals(3, owed.all().size); assertEquals(3, pack.cardLogDao.cardLogCount())
        dao.fail = false
        assertTrue(h.retryOwed()); assertEquals(0, pack.cardLogDao.cardLogCount()); assertTrue(owed.all().isEmpty())
        logs.forEach { l -> assertFalse(l, l.contains("f1")); assertFalse(l, l.contains("user:")) }
    }

    @Test fun retryThatFailsAgain_keepsTheObligation() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        h.add(ev(frame = "f1"))
        try { h.onFramesForgotten(listOf("f1")) } catch (_: HistoryDeletionPending) { }
        assertFalse(h.retryOwed()); assertTrue(h.hasOwed()); dao.fail = false; assertTrue(h.retryOwed()); assertFalse(h.hasOwed())
    }

    @Test fun forgetListener_failureIsOwedDurably_andTheForgetActionsAnswerSeesIt() = runBlocking {
        val prefs = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE); prefs.edit().clear().commit()
        val dao = FlakyDao(pack.cardLogDao); val h = PlaceCardHistory(dao, clock = { now }, log = { logs += it }, owed = PrefsHistoryOwed(prefs))
        PlaceGraph.installHistoryForTests(h)
        h.add(ev(frame = "f1"))
        ForgetListeners.framesForgotten(listOf("f1")) // the listener bus swallows, the obligation does not
        assertTrue(PlaceGraph.historyOwed(ctx)); assertFalse("Forget is not done while a row may remain", PlaceGraph.settleHistory(ctx))
        dao.fail = false
        assertTrue(PlaceGraph.settleHistory(ctx)); assertFalse(PlaceGraph.historyOwed(ctx)); assertEquals(0, pack.cardLogDao.cardLogCount())
        prefs.edit().clear().commit(); Unit
    }

    @Test fun deleteAll_throwsWhileHistoryRowsMayRemain() = runBlocking {
        val prefs = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE); prefs.edit().clear().commit()
        val dao = FlakyDao(pack.cardLogDao); PlaceGraph.installHistoryForTests(PlaceCardHistory(dao, clock = { now }, owed = PrefsHistoryOwed(prefs)))
        pack.cardLogDao.putCardLog(com.rokid.context.memory.place.PlaceCardLogEntity("r1", now, "wd:Q1", "T", "PHOTO", null, null, null, null, CardDelivery.PENDING))
        var thrown = false
        try { PlaceGraph.deleteAll(ctx) } catch (_: HistoryDeletionPending) { thrown = true }
        assertTrue("Delete all must not report done", thrown); assertTrue(PlaceGraph.historyOwed(ctx))
        dao.fail = false; assertTrue(PlaceGraph.settleHistory(ctx)); assertEquals(0, pack.cardLogDao.cardLogCount()); prefs.edit().clear().commit(); Unit
    }

    @Test fun insertionAfterPinRemoval_isRefused_andSerialisedWithTheDeletion() = runBlocking {
        val active = mutableSetOf(pin(1).id)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val h = PlaceCardHistory(pack.cardLogDao, clock = { now }, log = { logs += it }, newId = { "h${++seq}" }, pinActive = { gate.await(); it in active })
        val adding = async { h.add(ev(HistoryKind.PIN, place = pin(1).id)) }
        kotlinx.coroutines.yield()
        active.remove(pin(1).id) // the pin store was written
        val removing = async { h.onPinRemoved(listOf(pin(1).id)) }
        gate.complete(Unit)
        adding.await(); removing.await()
        assertTrue("no row of a removed pin survives, in either order", pack.cardLogDao.recentCardLog(10).none { it.placeId == pin(1).id })
        assertNull("a later insertion for the removed pin is refused", h.add(ev(HistoryKind.PIN_CLUSTER, place = "wd:Q1", members = listOf(pin(1).id, pin(2).id))))
        assertNotNull(h.add(ev(HistoryKind.PHOTO, place = "wd:Q1")))
    }

    @Test fun guardRefusal_insertsNothing() = runBlocking {
        assertNull(history.add(ev(), guard = { false })); assertEquals(0, pack.cardLogDao.cardLogCount())
        assertNotNull(history.add(ev(), guard = { true }))
    }

    @Test fun pinsRemoved_fencesAttemptsInFlight_withdrawsTheCard_andDeletesRows() = runBlocking {
        val p1 = pin(1)
        history.add(ev(HistoryKind.PIN, place = p1.id))
        val ticket = PlaceGraph.fence.ticket(); val epoch = PlaceGraph.port.epoch()
        PlaceGraph.pinsRemoved(history, listOf(p1.id))
        assertFalse("an attempt that holds the pin can no longer publish, store or add history", ticket.isOpen())
        assertTrue(PlaceGraph.port.epoch() > epoch); assertEquals(null, PlaceGraph.port.publishIfId(PlaceCard("x", "t", "l", "s", PlaceConfidence.PIN, "f"), epoch))
        assertTrue(history.recent().isEmpty())
    }

    // ---- Codex place fixes r3 ----

    /** SharedPreferences whose commit() can be made to return false (a full disk); everything else is the real thing. */
    private class FlakyPrefs(private val real: android.content.SharedPreferences) : android.content.SharedPreferences by real {
        @Volatile var failCommit = false
        override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor by real.edit() {
            private val e = real.edit()
            override fun putStringSet(k: String?, v: MutableSet<String>?) = apply { e.putStringSet(k, v) }
            override fun remove(k: String?) = apply { e.remove(k) }
            override fun commit(): Boolean = if (failCommit) false else e.commit()
            override fun apply() { e.apply() }
        }
    }

    /** P1-4: a commit() that returns false is reported, never assumed; the obligation is still held for this process and the Forget fails while the deletion cannot finish. */
    @Test fun owedObligationThatCannotBePersisted_isReported_keptInMemory_andFailsTheForget() = runBlocking {
        val real = ctx.getSharedPreferences("owed_r3", Context.MODE_PRIVATE); real.edit().clear().commit(); PrefsHistoryOwed.clearMemory()
        val prefs = FlakyPrefs(real); prefs.failCommit = true
        val owed = PrefsHistoryOwed(prefs)
        assertFalse("add reports the failed commit", owed.add("a:1"))
        assertEquals("not on disk", emptySet<String>(), real.getStringSet(PrefsHistoryOwed.KEY, emptySet()))
        assertEquals("but held for this process", setOf("a:1"), owed.all()); owed.remove("a:1"); PrefsHistoryOwed.clearMemory()
        val (dao, h) = failing(owed)
        h.add(ev(frame = "f1"))
        var thrown = false; try { h.onFramesForgotten(listOf("f1")) } catch (_: HistoryDeletionPending) { thrown = true }
        assertTrue("the Forget fails: the deletion failed and the obligation is not durable", thrown); assertTrue(h.hasOwed()); assertEquals(1, pack.cardLogDao.cardLogCount())
        dao.fail = false; prefs.failCommit = false
        assertTrue(h.retryOwed()); assertEquals(0, pack.cardLogDao.cardLogCount()); assertFalse(h.hasOwed())
        real.edit().clear().commit(); PrefsHistoryOwed.clearMemory(); Unit
    }

    @Test fun anObligationThatCannotBePersistedButFinishedRightAway_doesNotFailTheForget() = runBlocking {
        val real = ctx.getSharedPreferences("owed_r3", Context.MODE_PRIVATE); real.edit().clear().commit(); PrefsHistoryOwed.clearMemory()
        val prefs = FlakyPrefs(real); prefs.failCommit = true
        val h = PlaceCardHistory(pack.cardLogDao, clock = { now }, log = { logs += it }, newId = { "h${++seq}" }, owed = PrefsHistoryOwed(prefs))
        h.add(ev(frame = "f1")); h.onFramesForgotten(listOf("f1"))
        assertEquals(0, pack.cardLogDao.cardLogCount()); assertFalse(h.hasOwed()); PrefsHistoryOwed.clearMemory(); Unit
    }

    /** P1-3: the pin store is written, the mirror then fails: attempts are fenced at once and the history deletion still runs. */
    @Test fun mirrorFailureAfterThePinStoreWrite_stillFencesAndDeletesTheHistory() = runBlocking {
        val p1 = pin(1)
        history.add(ev(HistoryKind.PIN, place = p1.id))
        val ticket = PlaceGraph.fence.ticket()
        var fencedBeforeMirror = false
        var thrown = false
        try { PlaceGraph.afterPinWrite(history, listOf(p1.id)) { fencedBeforeMirror = !ticket.isOpen(); throw IllegalStateException("mirror") } } catch (e: IllegalStateException) { thrown = true }
        assertTrue("the mirror failure is reported", thrown); assertTrue("fenced BEFORE the mirror write could suspend", fencedBeforeMirror)
        assertTrue("the rows are gone anyway", history.recent().isEmpty()); assertFalse(history.hasOwed())
    }

    @Test fun mirrorFailure_withAFailingHistoryDeletion_leavesTheObligationOwed() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        val p1 = pin(1); h.add(ev(HistoryKind.PIN, place = p1.id))
        try { PlaceGraph.afterPinWrite(h, listOf(p1.id)) { throw IllegalStateException("mirror") } } catch (_: IllegalStateException) { }
        assertTrue("armed before the mirror, kept after both failed", owed.all().isNotEmpty()); assertEquals(1, pack.cardLogDao.cardLogCount())
        dao.fail = false; assertTrue(h.retryOwed()); assertEquals(0, pack.cardLogDao.cardLogCount())
    }

    /** P1-2: a pin cascade whose history deletion fails must not let the Forget report done, even though the cleanup was clean before the cascade. */
    @Test fun pinCascadeHistoryFailure_isNotReportedDone() = runBlocking {
        val prefs = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE); prefs.edit().clear().commit(); PrefsHistoryOwed.clearMemory()
        val dao = FlakyDao(pack.cardLogDao); dao.fail = false
        PlaceGraph.installHistoryForTests(PlaceCardHistory(dao, clock = { now }, log = { logs += it }, owed = PrefsHistoryOwed(prefs), pinActive = { id -> PlaceGraph.pins(ctx).pins().any { it.id == id } }))
        val p1 = pin(1); val p2 = pin(2)
        assertTrue(PlaceGraph.savePins(ctx, listOf(p1, p2)))
        pack.cardLogDao.putCardLog(com.rokid.context.memory.place.PlaceCardLogEntity("r1", now, p1.id, "T", "PIN", null, null, null, null, CardDelivery.PENDING))
        assertFalse("nothing owed before the cascade", PlaceGraph.historyOwed(ctx))
        dao.fail = true
        assertTrue("the cascade's removal succeeds; its history deletion is swallowed in savePins", PlaceGraph.savePins(ctx, listOf(p2)))
        assertTrue(PlaceGraph.historyOwed(ctx)); assertEquals(1, pack.cardLogDao.cardLogCount())
        assertTrue("so the Forget reports cleanup pending although the cache was clean", com.rokid.context.orchestration.forget.ForgetActions.cleanupFailed(ctx, true))
        dao.fail = false
        assertFalse("settled: done again", com.rokid.context.orchestration.forget.ForgetActions.cleanupFailed(ctx, true)); assertEquals(0, pack.cardLogDao.cardLogCount())
        prefs.edit().clear().commit(); PrefsHistoryOwed.clearMemory(); Unit
    }

    // ---- Codex place r3 P2-3: compaction and scope ----

    private suspend fun tryOwe(op: suspend () -> Unit) { try { op() } catch (_: HistoryDeletionPending) { } }

    @Test fun aDeleteAllTokenSubsumesEverythingArmedBeforeIt_andStillDeletesAllOfIt() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        h.add(ev(frame = "f1")); h.add(ev(HistoryKind.PIN, place = pin(1).id)); h.add(ev())
        tryOwe { h.onFramesForgotten(listOf("f1")) }; tryOwe { h.onRangeForgotten(0, now + 1) }; tryOwe { h.onPinRemoved(listOf(pin(1).id)) }
        assertEquals(3, owed.all().size)
        now += 1_000; tryOwe { h.clearAll() }
        assertEquals("one Delete all token replaces the frame, range and pin tokens", 1, owed.all().size); assertTrue(owed.all().single().startsWith("a:"))
        assertEquals("the rows are still there until the retry", 3, pack.cardLogDao.cardLogCount())
        dao.fail = false; now += 1_000; h.add(ev(place = "wd:Q9")) // made after the Delete all: survives
        assertTrue(h.retryOwed()); assertEquals(listOf("wd:Q9"), pack.cardLogDao.recentCardLog(10).map { it.placeId }); assertTrue(owed.all().isEmpty())
    }

    @Test fun aWiderRangeAndAnOlderDeleteAllSubsumeNarrowerTokens_butADisjointRangeStays() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        tryOwe { h.onRangeForgotten(10, 20) }; tryOwe { h.onRangeForgotten(50, 60) }; tryOwe { h.onRangeForgotten(0, 30) }
        assertEquals(setOf("r:50:60", "r:0:30"), owed.all())
        tryOwe { h.onRangeForgotten(0, 100) }
        assertEquals(setOf("r:0:100"), owed.all())
        // a Delete all token older than a later one: the later covers it
        now = 5_000; tryOwe { h.clearAll() }; now = 9_000; tryOwe { h.clearAll() }
        assertTrue(owed.all().contains("a:9000")); assertFalse(owed.all().contains("a:5000")); assertFalse(owed.all().contains("r:0:100"))
        dao.fail = false; assertTrue(h.retryOwed()); assertTrue(owed.all().isEmpty())
    }

    @Test fun anOwedSetOfAnOlderBuildIsCompactedAtRetry_withoutLosingDeletionScope() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        h.add(ev(frame = "f1"))
        listOf("r:0:10", "r:2:3", "r:100:200", "a:50", "a:20", "f:f1").forEach { owed.add(it) }
        dao.fail = false; assertTrue(h.retryOwed()) // exec of the survivors
        assertTrue(owed.all().isEmpty()); assertEquals("the frame token (no time) still ran", 0, pack.cardLogDao.cardLogCount())
        listOf("r:0:10", "r:2:3", "a:50", "a:20", "r:100:200", "f:f2").forEach { owed.add(it) }
        dao.fail = true; assertFalse(h.retryOwed())
        assertEquals("f and the widest time tokens only", setOf("a:50", "r:100:200", "f:f2"), owed.all())
    }

    @Test fun anUnrelatedPermanentlyFailingTokenDoesNotMakeALaterOperationReportPending() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (dao, h) = failing(owed)
        tryOwe { h.onFramesForgotten(listOf("old")) }
        assertTrue(h.hasOwed())
        h.beginScope(); assertFalse("an earlier Forget's token is not this operation's", h.scopePending())
        assertFalse("startup still retries it (and it keeps failing)", h.retryOwed()); assertFalse(h.scopePending())
        h.beginScope(); tryOwe { h.onFramesForgotten(listOf("mine")) }
        assertTrue("the operation that attempted a failing deletion reports it", h.scopePending())
        dao.fail = false; assertTrue(h.retryOwed()); assertFalse(h.scopePending())
    }

    @Test fun aDeleteAllThatSubsumedAFailedScopeTokenStillReportsPending() = runBlocking {
        val owed = InMemoryHistoryOwed(); val (_, h) = failing(owed)
        h.beginScope(); tryOwe { h.onFramesForgotten(listOf("mine")) }; now += 10; tryOwe { h.clearAll() }
        assertEquals(1, owed.all().size); assertTrue(h.scopePending())
    }
}
