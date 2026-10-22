package com.rokid.context.canvas.marks

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MarkRecorderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var epoch = DeletionEpoch("inst", 2, 1_000)
    private val logs = ArrayList<String>()
    private lateinit var dao: com.rokid.context.memory.db.CanvasSlice3Dao
    private lateinit var rec: MarkRecorder
    private val t0 = 1_000_000L

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        dao = MemoryGraph.get(ctx).db.canvasSlice3Dao()
        rec = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { t0 }, { logs += it })
    }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun mark(seq: Long, listen: Boolean = true, undo: Long? = null, counter: Long = 2) =
        MarkMessage(epoch = "e", seq = seq, atMs = 5, atElapsedMs = 5, listen = listen, undo = undo, deletionCounter = counter)
    private fun at(ms: Long) = MarkArrival(ms, mapped = true, receivedAtMs = ms)
    private fun labels() = runBlocking { dao.labels() }

    @Test fun markStoresOneMomentWithNoTextAndBumpsLabelRev() = runBlocking {
        assertTrue(rec.onMark(mark(1), at(t0)))
        val l = labels().single()
        assertEquals("MOMENT", l.kind); assertEquals("tap", l.source); assertEquals(t0, l.atMs)
        assertEquals(1L, dao.labelRev())
    }

    @Test fun doubleDeliveryIsIdempotentByMarkId() = runBlocking {
        rec.onMark(mark(1), at(t0)); rec.onMark(mark(1), at(t0 + 50))
        assertEquals(1, labels().size)
        assertEquals("labelRev must not move for a re-sent mark", 1L, dao.labelRev())
    }

    @Test fun preWipeMarkDropped() = runBlocking {
        epoch = DeletionEpoch("inst", 2, t0)
        assertTrue("still acknowledged", rec.onMark(mark(1), at(t0 - 1)))
        assertTrue(labels().isEmpty())
        rec.onMark(mark(2), at(t0)) // exactly at the wipe time is kept
        assertEquals(1, labels().size)
    }

    @Test fun aMarkTakenUnderAnOlderDeletionCounterIsDropped() = runBlocking {
        rec.onMark(mark(1, counter = 1), at(t0))
        assertTrue(labels().isEmpty())
        rec.onMark(mark(2, counter = -1), at(t0)) // the glasses knew no epoch: kept
        rec.onMark(mark(3, counter = 2), at(t0))
        assertEquals(2, labels().size)
    }

    @Test fun markBeforeListenJoins() = runBlocking {
        rec.onMark(mark(1), at(t0))
        rec.onListenStart(t0 + 2_000)
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 5_000)
        val l = labels().single()
        assertEquals("the tap mark became the voice mark, no second MOMENT", "voice", l.source)
        assertEquals("mk:e:1", l.labelId)
    }

    @Test fun listenBeforeMarkJoins() = runBlocking {
        rec.onListenStart(t0)
        rec.onMark(mark(1), at(t0 + 1_500))
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 5_000)
        assertEquals(listOf("mk:e:1"), labels().map { it.labelId })
    }

    @Test fun joinWindowEdgesAreInclusiveAtThreeSeconds() = runBlocking {
        // mark first
        rec.onMark(mark(1), at(t0)); rec.onListenStart(t0 + 3_000)
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 4_000)
        assertEquals(listOf("mk:e:1"), labels().map { it.labelId })
        setUp()
        rec.onMark(mark(1), at(t0)); rec.onListenStart(t0 + 3_001)
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 4_000)
        assertEquals("outside the window: a second, voice mark at the listen start", 2, labels().size)
        setUp()
        // listen first
        rec.onListenStart(t0 + 3_000); rec.onMark(mark(1), at(t0))
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 4_000)
        assertEquals(1, labels().size)
        setUp()
        rec.onListenStart(t0 + 3_001); rec.onMark(mark(1), at(t0))
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 4_000)
        assertEquals(2, labels().size)
        setUp()
        // the mark is earlier than the listen by 3 s the other way round
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 3_000))
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 4_000)
        assertEquals(1, labels().size)
    }

    @Test fun aListenJoinsOnlyOneMarkAndAMarkOnlyOneListen() = runBlocking {
        rec.onMark(mark(1), at(t0)); rec.onMark(mark(2), at(t0 + 100))
        rec.onListenStart(t0 + 50)
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 500)
        assertEquals("one tap mark kept as is, one retagged: no third row", 2, labels().size)
        rec.onListenStart(t0 + 60) // a second listen cannot take the already-joined mark
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 600)
        assertEquals("the 2nd listen joined the other tap: no new row", 2, labels().count { it.kind == "MOMENT" })
    }

    @Test fun redeliveredMarkNeverRebindsToALaterListen() = runBlocking {
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 10))
        rec.onListenStart(t0 + 20_000); rec.onMark(mark(1), at(t0 + 20_010)) // the resend of the same mark
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 21_000)
        assertEquals("the resend is a duplicate: the 2nd listen gets its own voice mark", 2, labels().size)
    }

    @Test fun noDuplicateMoment() = runBlocking {
        rec.onListenStart(t0)
        rec.applyVoice(listOf(VoiceMark.Mark(null), VoiceMark.Mark(null), VoiceMark.Retro(null)), true, t0 + 1_000)
        assertEquals(1, labels().count { it.kind == "MOMENT" })
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 1_500) // the same listen again
        assertEquals(1, labels().count { it.kind == "MOMENT" })
    }

    @Test fun voiceMarkWithNoTapIsAnchoredAtTheListenStartWithIntent() = runBlocking {
        rec.onListenStart(t0)
        rec.applyVoice(listOf(VoiceMark.Mark("driving")), true, t0 + 4_000)
        val m = labels().first { it.kind == "MOMENT" }; val i = labels().first { it.kind == "INTENT" }
        assertEquals(t0, m.atMs); assertEquals("voice", m.source)
        assertEquals("driving", i.name); assertEquals(t0, i.atMs)
    }

    @Test fun retroMarkWindow() = runBlocking {
        rec.onListenStart(t0)
        rec.applyVoice(listOf(VoiceMark.Retro(null)), true, t0 + 2_000)
        val m = labels().single()
        assertEquals("retro", m.source)
        assertEquals("the previous ~10 s end at the listen start", t0, m.atMs)
    }

    @Test fun phoneMicVoiceMarkIsPhoneVoice() = runBlocking {
        rec.onPhoneListenStart(t0)
        rec.applyVoice(listOf(VoiceMark.Mark(null)), false, t0 + 3_000)
        assertEquals("phone_voice", labels().single().source)
    }

    @Test fun undoCommandDeletesBoundMark() = runBlocking {
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 100))
        rec.onMark(mark(2), at(t0 - 60_000)) // an older, unrelated mark
        val rev = dao.labelRev()
        rec.applyVoice(listOf(VoiceMark.Undo), true, t0 + 2_000)
        assertEquals(listOf("mk:e:2"), labels().map { it.labelId })
        assertTrue(dao.labelRev() > rev)
    }

    @Test fun undoWithoutABoundMarkDeletesTheNewestWithinTwoMinutes() = runBlocking {
        rec.onMark(mark(1, listen = false), at(t0 - 60_000)); rec.onMark(mark(2, listen = false), at(t0 - 30_000))
        rec.onListenStart(t0 + 20_000)
        rec.applyVoice(listOf(VoiceMark.Undo), true, t0)
        assertEquals(listOf("mk:e:1"), labels().map { it.labelId })
    }

    @Test fun undoWindowEdgeIsTwoMinutesInclusive() = runBlocking {
        rec.onMark(mark(1, listen = false), at(t0 - 120_000))
        rec.onListenStart(t0 + 500_000)
        rec.applyVoice(listOf(VoiceMark.Undo), true, t0)
        assertTrue(labels().isEmpty())
        rec.onMark(mark(2, listen = false), at(t0 - 120_001))
        rec.applyVoice(listOf(VoiceMark.Undo), true, t0)
        assertEquals("120.001 s old: out of reach", 1, labels().size)
    }

    @Test fun undoDeletesTheIntentRowToo() = runBlocking {
        rec.onListenStart(t0)
        rec.applyVoice(listOf(VoiceMark.Mark("driving")), true, t0 + 1_000)
        rec.applyVoice(listOf(VoiceMark.Undo), true, t0 + 2_000)
        assertTrue(labels().isEmpty())
    }

    @Test fun pcMarkUndoDeletesThatMarkAndIsIdempotent() = runBlocking {
        rec.onMark(mark(1), at(t0))
        rec.onMark(mark(2, listen = false, undo = 1), at(t0 + 500))
        assertTrue(labels().isEmpty())
        val rev = dao.labelRev()
        rec.onMark(mark(2, listen = false, undo = 1), at(t0 + 500))
        assertEquals(rev, dao.labelRev())
        // an undo for a mark never seen does nothing
        assertTrue(rec.onMark(mark(9, listen = false, undo = 77), at(t0)))
    }

    @Test fun anUndoneTapMarkNeverJoinsALaterListen() = runBlocking {
        rec.onMark(mark(1), at(t0)); rec.onMark(mark(2, listen = false, undo = 1), at(t0))
        rec.onListenStart(t0 + 100)
        rec.applyVoice(listOf(VoiceMark.Mark(null)), true, t0 + 200)
        assertEquals(listOf("voice"), labels().map { it.source })
    }

    @Test fun anOrdinaryQuestionAfterATapKeepsTheRowAsAskTap() = runBlocking {
        rec.onListenStart(t0); rec.onMark(mark(1), at(t0 + 10))
        rec.onAsk(true)
        assertEquals("ask_tap", labels().single().source)
    }

    @Test fun labelsAreNeverTextAndLogsAreEventNames() = runBlocking {
        rec.onListenStart(t0)
        rec.applyVoice(listOf(VoiceMark.Mark("driving")), true, t0 + 1_000)
        labels().forEach { assertTrue(it.name == "MOMENT" || it.name == "driving") }
        assertTrue(logs.all { it.length < 60 })
        assertNull(labels().firstOrNull { it.provenance.length > 20 })
    }
}
