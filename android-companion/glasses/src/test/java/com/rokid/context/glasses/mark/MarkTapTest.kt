package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MarkTapTest {
    private class MemStore : MarkStore {
        var text: String? = null
        override fun read() = text
        override fun write(text: String): Boolean { this.text = text; return true }
    }

    private val sent = mutableListOf<MarkMessage>()
    private var linkUp = true
    private var enabled = true
    private var wall = 5_000_000L

    private fun kotlinx.coroutines.test.TestScope.tap(): Triple<MarkTap, MarkOutbox, MarkCue> {
        val o = MarkOutbox(MemStore(), wallClock = { wall }, newEpoch = { "ep" })
        val cue = MarkCue(backgroundScope)
        return Triple(MarkTap(o, cue, send = { m -> if (linkUp) { sent += m; true } else false }, enabled = { enabled }, wallClock = { wall }, elapsed = { 777 }, bootCount = { 9 }), o, cue)
    }

    @Test fun aTapRecordsSendsAndShowsTheCueAtOnce() = runTest {
        val (t, o, cue) = tap()
        val seq = t.onTap(listen = true)
        runCurrent()
        assertEquals(1L, seq)
        val m = sent.single()
        assertEquals(MarkMessage(epoch = "ep", seq = 1, atMs = 5_000_000, atElapsedMs = 777, bootCount = 9, listen = true), m.copy(sessionStartMs = 0))
        assertEquals(MarkCue.MARKED, cue.text.value)
        assertEquals(1, o.size, "kept until the phone acks")
    }

    @Test fun theCueClearsAfterSixHundredMs() = runTest {
        val (t, _, cue) = tap()
        t.onTap(true); runCurrent()
        advanceTimeBy(599); assertEquals(MarkCue.MARKED, cue.text.value)
        advanceTimeBy(2); assertNull(cue.text.value)
    }

    @Test fun withTheLinkDownTheMarkIsQueuedNotLostAndTheCueStillShows() = runTest {
        linkUp = false
        val (t, o, cue) = tap()
        t.onTap(true); runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(1, o.size)
        assertEquals(MarkCue.MARKED, cue.text.value, "the cue never waits for the phone")
    }

    @Test fun markByTapOffTakesNoMark() = runTest {
        enabled = false
        val (t, o, cue) = tap()
        assertNull(t.onTap(true))
        assertEquals(0, o.size); assertTrue(sent.isEmpty()); assertNull(cue.text.value)
    }

    @Test fun doubleTapUndoSendsAnUndoForThisListensMarkAndChangesTheCue() = runTest {
        val (t, o, cue) = tap()
        val seq = t.onTap(true)!!
        t.onUndo(); runCurrent()
        val u = sent.last()
        assertEquals(seq, u.undo)
        assertFalse(u.listen)
        assertEquals(MarkCue.UNDONE, cue.text.value)
        assertEquals(2, o.size)
        t.onUndo()
        assertEquals(2, sent.size, "a second undo of the same mark is nothing")
    }

    @Test fun undoWithoutATapIsNothing() = runTest {
        val (t, o, _) = tap()
        t.onUndo()
        assertEquals(0, o.size); assertTrue(sent.isEmpty())
    }

    @Test fun aListenThatEndedNormallyCannotBeUndoneLater() = runTest {
        val (t, _, _) = tap()
        t.onTap(true)
        t.onListenFinished()
        t.onUndo()
        assertEquals(1, sent.size)
    }

    @Test fun flushSendsEveryQueuedMarkOldestFirst() = runTest {
        linkUp = false
        val (t, _, _) = tap()
        t.onTap(true); t.onTap(false)
        linkUp = true
        t.flush()
        assertEquals(listOf(1L, 2L), sent.map { it.seq })
    }
}
