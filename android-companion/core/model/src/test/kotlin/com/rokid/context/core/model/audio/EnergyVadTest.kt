package com.rokid.context.core.model.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyVadTest {
    /** One 100 ms `pc_audio` chunk (3 200 bytes) of a sine with [amp], or silence when amp = 0. */
    private fun chunk(amp: Int): ByteArray {
        val b = ByteBuffer.allocate(Pcm.CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until Pcm.CHUNK_BYTES / 2) b.putShort((amp * sin(i / 5.0)).toInt().toShort())
        return b.array()
    }

    @Test fun endsAfterOneAndAHalfSecondsOfSilenceFollowingSpeech() {
        val vad = EnergyVad()
        repeat(5) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(4_000))) }
        repeat(14) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(0))) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0)))
    }

    @Test fun aOneSecondPauseInsideAQuestionDoesNotEndIt() {
        val vad = EnergyVad()
        repeat(5) { vad.feed(chunk(4_000)) }
        repeat(10) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(0))) }
        repeat(5) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(4_000))) }
        repeat(14) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(0))) }
        assertEquals(VadDecision.END_OF_SPEECH, vad.feed(chunk(0)))
    }

    @Test fun nothingSaidEndsAsNoSpeechAfterFiveSeconds() {
        val vad = EnergyVad()
        repeat(49) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(0))) }
        assertEquals(VadDecision.NO_SPEECH, vad.feed(chunk(0)))
    }

    @Test fun capsAtFifteenSeconds() {
        val vad = EnergyVad()
        repeat(149) { assertEquals(VadDecision.CONTINUE, vad.feed(chunk(4_000))) }
        assertEquals(VadDecision.MAX_REACHED, vad.feed(chunk(4_000)))
    }

    @Test fun resetStartsOverAndAnEmptyChunkChangesNothing() {
        val vad = EnergyVad()
        repeat(5) { vad.feed(chunk(4_000)) }
        repeat(14) { vad.feed(chunk(0)) }
        vad.reset()
        assertEquals(VadDecision.CONTINUE, vad.feed(chunk(0)))
        assertEquals(VadDecision.CONTINUE, vad.feed(ByteArray(0)))
        assertEquals(100L, Pcm.millis(Pcm.CHUNK_BYTES.toLong()))
    }
}
