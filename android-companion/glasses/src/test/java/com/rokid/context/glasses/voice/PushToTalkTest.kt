package com.rokid.context.glasses.voice

import com.rokid.context.core.model.audio.Pcm
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PushToTalkTest {
    private class FakeLink(var startOk: Boolean = true, var audioOkFor: Int = Int.MAX_VALUE) : PttLink {
        val sent = CopyOnWriteArrayList<String>()
        override fun start(): Boolean { sent += "start"; return startOk }
        override fun audio(base64: String): Boolean { sent += "audio"; return sent.count { it == "audio" } <= audioOkFor }
        override fun end() { sent += "end" }
        override fun cancel() { sent += "cancel" }
    }

    /** Blocks in read() until a permit, like AudioRecord waiting for the next buffer; loud audio (speech to the VAD). */
    private class BlockingPcm : PcmSource {
        val permits = Semaphore(0)
        /** The size of every buffer PushToTalk asked to fill (preflight F8). */
        val readSizes = CopyOnWriteArrayList<Int>()
        @Volatile var closed = false
        override fun start() = Unit
        override fun read(buf: ByteArray): Int { readSizes += buf.size; permits.acquire(); if (closed) return -1; buf.fill(40.toByte()); return buf.size }
        override fun close() { closed = true; permits.release(100) }
    }

    /** [speechChunks] loud chunks, then silence, without blocking. */
    private class ScriptedPcm(private val speechChunks: Int) : PcmSource {
        private var n = 0
        override fun start() = Unit
        override fun read(buf: ByteArray): Int { buf.fill((if (n++ < speechChunks) 40 else 0).toByte()); return buf.size }
        override fun close() = Unit
    }

    private fun awaitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue(cond(), "condition not reached")
    }

    @Test fun aSecondTapSendsListenEndAfterTheAudio() {
        val link = FakeLink(); val pcm = BlockingPcm()
        val ptt = PushToTalk(link, sourceFactory = { pcm })
        assertTrue(ptt.start())
        assertTrue(ptt.recording.value)
        awaitUntil { "start" in link.sent }
        pcm.permits.release(3)
        awaitUntil { link.sent.count { it == "audio" } == 3 }
        ptt.stop(); pcm.permits.release(1)
        awaitUntil { !ptt.recording.value }
        assertEquals(4, link.sent.count { it == "audio" }, "the chunk in flight at the tap is trailing speech: it is sent")
        assertEquals("end", link.sent.last())
        assertEquals(listOf("start"), link.sent.take(1))
    }

    @Test fun twoPointTwoSecondsOfSilenceEndsTheUtterance() {
        val link = FakeLink()
        val ptt = PushToTalk(link, sourceFactory = { ScriptedPcm(speechChunks = 5) })
        ptt.start()
        awaitUntil { !ptt.recording.value }
        assertEquals(listOf("start") + List(27) { "audio" } + "end", link.sent.toList())
    }

    /** BUG-R3-3: the VAD ended the listen just before the wearer's "send" tap, and that tap opened a second listen. */
    @Test fun aTapJustAfterAnAutoEndIsTheSendNotANewAsk() {
        val link = FakeLink()
        var now = 10_000L
        val ptt = PushToTalk(link, sourceFactory = { ScriptedPcm(speechChunks = 5) }, clock = { now })
        ptt.start()
        awaitUntil { !ptt.recording.value }
        now += 1_400
        assertTrue(ptt.takeLateTap(), "within 1.5 s of the auto-end: the tap was the send")
        assertFalse(ptt.takeLateTap(), "one tap is absorbed, the next one asks again")
        assertEquals(1, link.sent.count { it == "start" })
    }

    @Test fun aTapAfterTheGraceWindowAsksAgain() {
        var now = 10_000L
        val ptt = PushToTalk(FakeLink(), sourceFactory = { ScriptedPcm(speechChunks = 5) }, clock = { now })
        ptt.start()
        awaitUntil { !ptt.recording.value }
        now += 1_501
        assertFalse(ptt.takeLateTap())
    }

    /** Only an auto-end opens the window: a tap-ended or cancelled listen leaves the next tap a new ask. */
    @Test fun aTapEndedOrCancelledListenHasNoGraceWindow() {
        val pcm = BlockingPcm()
        val ptt = PushToTalk(FakeLink(), sourceFactory = { pcm }, clock = { 10_000L })
        ptt.start(); pcm.permits.release(2)
        ptt.stop(); pcm.permits.release(1)
        awaitUntil { !ptt.recording.value }
        assertFalse(ptt.takeLateTap())
        val pcm2 = BlockingPcm()
        val cancelled = PushToTalk(FakeLink(), sourceFactory = { pcm2 }, clock = { 10_000L })
        cancelled.start(); cancelled.cancel(); pcm2.permits.release(1)
        awaitUntil { !cancelled.recording.value }
        assertFalse(cancelled.takeLateTap())
    }

    @Test fun cancelStopsWithoutAListenEnd() {
        val link = FakeLink(); val pcm = BlockingPcm()
        val ptt = PushToTalk(link, sourceFactory = { pcm })
        ptt.start(); awaitUntil { "start" in link.sent }
        assertTrue(ptt.cancel(), "the phone had the ListenStart: the caller owes it a Cancel")
        assertFalse(ptt.cancel(), "a second cancel owes nothing")
        pcm.permits.release(1)
        awaitUntil { !ptt.recording.value }
        assertFalse("end" in link.sent, "the phone already has the Cancel")
        assertFalse("audio" in link.sent, "cancel discards the chunk in flight")
    }

    @Test fun noPermissionOrABrokenMicSendsNothing() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val link = FakeLink()
        val denied = PushToTalk(link, hasPermission = { false }).apply { onProblem = { problems += it } }
        assertFalse(denied.start())
        val broken = PushToTalk(link, sourceFactory = { throw IllegalStateException("AudioRecord not initialized") }).apply { onProblem = { problems += it } }
        assertTrue(broken.start())
        awaitUntil { !broken.recording.value && problems.size == 2 }
        assertEquals(listOf(PttProblem.NO_PERMISSION, PttProblem.MIC_UNAVAILABLE), problems.toList())
        assertTrue(link.sent.isEmpty())
    }

    @Test fun aMicThatFailsToStartIsStillReleased() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val closed = AtomicBoolean(false)
        val failing = object : PcmSource {
            override fun start() = throw IllegalStateException("startRecording failed")
            override fun read(buf: ByteArray) = -1
            override fun close() = closed.set(true)
        }
        val link = FakeLink()
        val ptt = PushToTalk(link, sourceFactory = { failing }).apply { onProblem = { problems += it } }
        assertTrue(ptt.start())
        awaitUntil { !ptt.recording.value && problems.isNotEmpty() }
        assertTrue(closed.get(), "the mic is released on the error path")
        assertEquals(listOf(PttProblem.MIC_UNAVAILABLE), problems.toList())
        assertTrue(link.sent.isEmpty())
    }

    /** Minor 4: a negative read() (e.g. ERROR_INVALID_OPERATION, ERROR_DEAD_OBJECT) is a mic error, not an end. */
    @Test fun aFailedReadIsAMicErrorWithoutAListenEnd() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val link = FakeLink()
        val broken = object : PcmSource {
            private var n = 0
            override fun start() = Unit
            override fun read(buf: ByteArray): Int { if (n++ > 0) return -3; buf.fill(40.toByte()); return buf.size }
            override fun close() = Unit
        }
        val ptt = PushToTalk(link, sourceFactory = { broken }).apply { onProblem = { problems += it } }
        ptt.start(); awaitUntil { !ptt.recording.value }
        assertEquals(listOf(PttProblem.MIC_UNAVAILABLE), problems.toList())
        assertEquals(listOf("start", "audio", "cancel"), link.sent.toList(), "no pc_listen_end; the phone is told to drop the ask")
    }

    /** Minor 6: a cancel racing the ListenStart waits for it, so the caller's Cancel always follows it. */
    @Test fun aCancelDuringListenStartWaitsForItAndOwesTheCancel() {
        val entered = Semaphore(0); val gate = Semaphore(0)
        val link = object : PttLink {
            val sent = CopyOnWriteArrayList<String>()
            override fun start(): Boolean { sent += "start"; entered.release(); gate.acquire(); sent += "started"; return true }
            override fun audio(base64: String) = true
            override fun end() { sent += "end" }
            override fun cancel() { sent += "cancel" }
        }
        val ptt = PushToTalk(link, sourceFactory = { BlockingPcm() })
        ptt.start(); entered.acquire()
        Thread { val owed = ptt.cancel(); link.sent += "cancel() returned $owed" }.start()
        Thread.sleep(100)
        assertFalse(link.sent.any { it.startsWith("cancel()") }, "cancel waits for the ListenStart in flight")
        gate.release()
        awaitUntil { link.sent.any { it.startsWith("cancel()") } }
        assertEquals(listOf("start", "started", "cancel() returned true"), link.sent.toList())
    }

    /** Minor 9: a read() that never returns (hung HAL) must not hold the mic, the flush and Ask forever. */
    @Test fun aStalledReadIsCancelledAndReleasesTheMic() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val link = FakeLink()
        val closed = AtomicBoolean(false)
        val hung = object : PcmSource {
            override fun start() = Unit
            override fun read(buf: ByteArray): Int { Semaphore(0).acquire(); return -1 } // close() does not unblock it
            override fun close() = closed.set(true)
        }
        val ptt = PushToTalk(link, sourceFactory = { hung }, readTimeoutMs = 300).apply { onProblem = { problems += it } }
        ptt.start()
        awaitUntil { !ptt.recording.value }
        assertTrue(closed.get(), "the mic is released")
        assertEquals(listOf(PttProblem.MIC_UNAVAILABLE), problems.toList())
        assertEquals(listOf("start", "cancel"), link.sent.toList(), "the phone drops the ask; no pc_listen_end")
    }

    /** Fix round 2: a close() that hangs too (the same hung HAL) cannot swallow the owed Cancel or the toast. */
    @Test fun aWatchdogWhoseCloseHangsHasAlreadySentTheCancelAndTheToast() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val link = FakeLink()
        val closeEntered = Semaphore(0)
        val hung = object : PcmSource {
            override fun start() = Unit
            override fun read(buf: ByteArray): Int { Semaphore(0).acquire(); return -1 }
            override fun close() { closeEntered.release(); Semaphore(0).acquire() }
        }
        val ptt = PushToTalk(link, sourceFactory = { hung }, readTimeoutMs = 300).apply { onProblem = { problems += it } }
        ptt.start()
        assertTrue(closeEntered.tryAcquire(3, TimeUnit.SECONDS), "the watchdog fired")
        assertEquals(listOf("start", "cancel"), link.sent.toList(), "the Cancel went out before the close")
        assertEquals(listOf(PttProblem.MIC_UNAVAILABLE), problems.toList(), "the toast came before the close")
        assertFalse(ptt.recording.value)
    }

    @Test fun aStalledReadDoesNotLockAsk() {
        val first = AtomicBoolean(true)
        val hung = object : PcmSource {
            override fun start() = Unit
            override fun read(buf: ByteArray): Int { if (first.getAndSet(false)) Semaphore(0).acquire(); return -1 }
            override fun close() = Unit
        }
        val ptt = PushToTalk(FakeLink(), sourceFactory = { hung }, readTimeoutMs = 300)
        ptt.start()
        awaitUntil { !ptt.recording.value }
        assertTrue(ptt.start(), "Ask works again after the watchdog")
        awaitUntil { !ptt.recording.value }
    }

    @Test fun aRefusedStartOrADeadLinkStops() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val refused = FakeLink(startOk = false)
        PushToTalk(refused, sourceFactory = { ScriptedPcm(100) }).apply { onProblem = { problems += it } }.also { it.start(); awaitUntil { !it.recording.value } }
        assertEquals(listOf("start"), refused.sent.toList())
        val dying = FakeLink(audioOkFor = 2)
        PushToTalk(dying, sourceFactory = { ScriptedPcm(100) }).apply { onProblem = { problems += it } }.also { it.start(); awaitUntil { !it.recording.value } }
        assertEquals(3, dying.sent.count { it == "audio" })
        assertFalse("end" in dying.sent, "a dead link gets no pc_listen_end")
        assertEquals(listOf(PttProblem.LINK_DOWN, PttProblem.LINK_DOWN), problems.toList())
    }

    @Test fun startWhileRecordingIsANoOpAndChunksAreOneHundredMs() {
        val link = FakeLink(); val pcm = BlockingPcm()
        val ptt = PushToTalk(link, sourceFactory = { pcm })
        ptt.start(); assertFalse(ptt.start())
        awaitUntil { "start" in link.sent }
        pcm.permits.release(1)
        awaitUntil { link.sent.count { it == "audio" } == 1 }
        ptt.stop(); pcm.permits.release(1); awaitUntil { !ptt.recording.value }
        assertEquals(1, link.sent.count { it == "start" }, "the second start did not open a second capture")
        assertTrue(pcm.readSizes.isNotEmpty())
        assertEquals(setOf(3_200), pcm.readSizes.toSet(), "every read asks for one 100 ms chunk")
    }
}
