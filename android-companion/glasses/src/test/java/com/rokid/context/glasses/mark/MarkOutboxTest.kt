package com.rokid.context.glasses.mark

import com.rokid.context.core.model.MarkAck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MarkOutboxTest {
    private class MemStore(var text: String? = null, var failWrites: Boolean = false) : MarkStore {
        override fun read(): String? = text
        override fun write(text: String): Boolean { if (failWrites) return false; this.text = text; return true }
    }

    private val store = MemStore()
    private var wall = 1_000_000_000L
    private var ids = 0
    private fun outbox(s: MarkStore = store) = MarkOutbox(s, wallClock = { wall }, newEpoch = { "ep${++ids}" })
    private fun MarkOutbox.mark(at: Long = wall, listen: Boolean = true, undo: Long? = null) = record(at, atElapsedMs = 5, bootCount = 3, listen = listen, undo = undo)

    @Test fun aMarkSaysItWillTakeAPhotoOnlyWhenAskedTo() {
        val o = outbox()
        assertFalse(o.record(wall, 5, 3, listen = true).photo)
        val m = o.record(wall, 5, 3, listen = true, photo = true)
        assertTrue(m.photo)
        assertTrue(outbox().pending().last().photo, "the flag survives a restart: a resent mark still promises its photo")
    }

    @Test fun recordPersistsBeforeItReturns() {
        val o = outbox()
        val m = o.mark()
        assertEquals(1L, m.seq)
        assertTrue(store.text!!.contains("\"seq\":1"), "the queue is on disk when record returns")
        assertEquals(m, outbox().pending().single(), "a restart still holds it")
    }

    @Test fun epochIsGeneratedOnceAndSeqIsMonotonicAcrossRestarts() {
        val a = outbox().mark()
        val o2 = outbox()
        val b = o2.mark()
        assertEquals(a.epoch, b.epoch)
        assertEquals(listOf(1L, 2L), listOf(a.seq, b.seq))
        o2.onAck(MarkAck(epoch = a.epoch, seqs = listOf(1, 2)))
        assertEquals(3L, outbox().mark().seq, "acked seqs are never reused")
    }

    @Test fun ackRemovesOnlyMatchingEpochAndSeq() {
        val o = outbox()
        val a = o.mark(); val b = o.mark()
        o.onAck(MarkAck(epoch = "other", seqs = listOf(a.seq, b.seq)))
        assertEquals(2, o.pending().size)
        o.onAck(MarkAck(epoch = a.epoch, seqs = listOf(a.seq)))
        assertEquals(listOf(b.seq), o.pending().map { it.seq })
    }

    @Test fun eachQueuedMarkStoresTheKnownDeletionCounter() {
        val o = outbox()
        assertEquals(-1L, o.mark().deletionCounter, "no epoch seen yet")
        o.onEpoch("inst", 4)
        assertEquals(4L, o.mark().deletionCounter)
        o.onEpoch("inst", 5)
        assertEquals(5L, o.mark().deletionCounter)
    }

    @Test fun newEpochClearsQueue() {
        val o = outbox()
        o.onEpoch("inst", 1)
        o.mark(); o.mark()
        o.onEpoch("inst", 1)
        assertEquals(2, o.pending().size, "the same epoch changes nothing")
        o.onEpoch("inst", 2) // Delete all on the phone
        assertTrue(o.pending().isEmpty())
        o.mark()
        o.onEpoch("inst2", 0) // reinstall or re-pairing
        assertTrue(o.pending().isEmpty())
        assertTrue(outbox().pending().isEmpty(), "and it is on disk")
    }

    @Test fun anOlderCounterOfTheSameInstallIsStaleAndKeepsTheQueue() {
        val o = outbox()
        o.onEpoch("inst", 5)
        o.mark()
        o.onEpoch("inst", 3)
        assertEquals(1, o.pending().size)
        assertEquals(5L, o.mark().deletionCounter)
    }

    @Test fun adoptingTheFirstEpochKeepsMarksTakenBeforeIt() {
        val o = outbox()
        o.mark()
        o.onEpoch("inst", 0)
        assertEquals(1, o.pending().size)
    }

    @Test fun oldQueuedMarkResentAfterReconnect() {
        val o = outbox()
        val old = o.mark(at = wall - 23 * 3_600_000L)
        wall += 1_000
        assertEquals(listOf(old), outbox().pending(), "23 h old: still sent")
        wall += 2 * 3_600_000L
        assertTrue(outbox().pending().isEmpty(), "older than 24 h: dropped")
    }

    @Test fun aBurstIsAtMostTwentyOldestFirst() {
        val o = outbox()
        repeat(25) { o.mark() }
        assertEquals((1L..20L).toList(), o.pending().map { it.seq })
    }

    @Test fun theQueueIsCappedAtTwoHundredDroppingTheOldest() {
        val o = outbox()
        repeat(205) { o.mark() }
        assertEquals(200, o.size)
        assertEquals(6L, o.all().first().seq)
    }

    @Test fun undoIsAQueuedEntryThatNamesTheMarkItUndoes() {
        val o = outbox()
        val a = o.mark()
        val u = o.mark(listen = false, undo = a.seq)
        assertEquals(a.seq, u.undo)
        assertNotEquals(a.seq, u.seq)
        assertEquals(2, outbox().pending().size)
    }

    @Test fun anUnreadableStoreIsAnEmptyQueueNotACrash() {
        val o = outbox(MemStore("garbage\n{not json"))
        assertTrue(o.pending().isEmpty())
        assertEquals(1L, o.mark().seq)
    }

    @Test fun aFailedWriteStillReturnsTheMarkSoItGoesOut() {
        val s = MemStore(failWrites = true)
        val o = outbox(s)
        val m = o.mark()
        assertEquals(1L, m.seq)
        assertFalse(o.lastWriteOk)
        assertEquals(1, o.pending().size)
    }
}
