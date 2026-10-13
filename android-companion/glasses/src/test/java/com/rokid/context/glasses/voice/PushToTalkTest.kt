package com.rokid.context.glasses.voice

import com.rokid.context.core.model.audio.Pcm
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
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
        assertEquals("end", link.sent.last())
        assertEquals(listOf("start"), link.sent.take(1))
    }

    @Test fun oneAndAHalfSecondsOfSilenceEndsTheUtterance() {
        val link = FakeLink()
        val ptt = PushToTalk(link, sourceFactory = { ScriptedPcm(speechChunks = 5) })
        ptt.start()
        awaitUntil { !ptt.recording.value }
        assertEquals(listOf("start") + List(20) { "audio" } + "end", link.sent.toList())
    }

    @Test fun cancelStopsWithoutAListenEnd() {
        val link = FakeLink(); val pcm = BlockingPcm()
        val ptt = PushToTalk(link, sourceFactory = { pcm })
        ptt.start(); awaitUntil { "start" in link.sent }
        ptt.cancel(); pcm.permits.release(1)
        awaitUntil { !ptt.recording.value }
        assertFalse("end" in link.sent, "the phone already has the Cancel")
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

    @Test fun aRefusedStartOrADeadLinkStops() {
        val problems = CopyOnWriteArrayList<PttProblem>()
        val refused = FakeLink(startOk = false)
        PushToTalk(refused, sourceFactory = { ScriptedPcm(100) }).apply { onProblem = { problems += it } }.also { it.start(); awaitUntil { !it.recording.value } }
        assertEquals(listOf("start"), refused.sent.toList())
        val dying = FakeLink(audioOkFor = 2)
        PushToTalk(dying, sourceFactory = { ScriptedPcm(100) }).apply { onProblem = { problems += it } }.also { it.start(); awaitUntil { !it.recording.value } }
        assertEquals(3, dying.sent.count { it == "audio" })
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
