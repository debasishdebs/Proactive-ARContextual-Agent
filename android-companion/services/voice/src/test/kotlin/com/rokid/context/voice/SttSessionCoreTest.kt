package com.rokid.context.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SttSessionCoreTest {
    private val partials = mutableListOf<String>()
    private val finals = mutableListOf<SttResult>()
    private var suspect = false
    private var finishedCalls = 0
    private val core = SttSessionCore(object : SttListener {
        override fun onPartial(text: String) { partials += text }
        override fun onFinal(result: SttResult) { finals += result }
    }) { suspect }.also { it.onFinished = { finishedCalls++ } }

    @Test fun aNullFinalAfterPartialsUsesTheLastPartial() {
        core.onPartial(listOf("what do"))
        core.onPartial(listOf("what do I need from the store"))
        core.onPartial(listOf(""))
        core.onResults(null)
        assertEquals(listOf("what do", "what do I need from the store"), partials)
        assertEquals(listOf<SttResult>(SttResult.Text("what do I need from the store")), finals)
        assertEquals(1, finishedCalls)
    }

    @Test fun aPartialAfterTheGuardTripsAbortsTheSession() {
        core.onPartial(listOf("hello"))
        suspect = true
        core.onPartial(listOf("hello there"))
        assertEquals(listOf("hello"), partials, "nothing heard from the phone mic is shown")
        assertEquals(listOf<SttResult>(SttResult.FallbackSuspected), finals)
        core.onResults(listOf("hello there"))
        assertEquals(1, finals.size, "one final only")
    }

    @Test fun resultsAreJudgedByTheGuardToo() {
        suspect = true
        core.onResults(listOf("from the phone mic"))
        assertEquals(listOf<SttResult>(SttResult.FallbackSuspected), finals)
    }

    @Test fun errorsMapToShortOutcomes() {
        fun outcome(code: Int, partial: String? = null): SttResult {
            val out = mutableListOf<SttResult>()
            val c = SttSessionCore(object : SttListener { override fun onPartial(text: String) = Unit; override fun onFinal(result: SttResult) { out += result } }) { false }
            partial?.let { c.onPartial(listOf(it)) }
            c.onError(code)
            return out.single()
        }
        assertEquals(SttResult.NoSpeech, outcome(SttSessionCore.ERROR_NO_MATCH))
        assertEquals(SttResult.NoSpeech, outcome(SttSessionCore.ERROR_SPEECH_TIMEOUT))
        assertEquals(SttResult.PackMissing, outcome(SttSessionCore.ERROR_LANGUAGE_UNAVAILABLE))
        assertEquals(SttResult.PackMissing, outcome(SttSessionCore.ERROR_LANGUAGE_NOT_SUPPORTED))
        assertEquals(SttResult.NoPermission, outcome(SttSessionCore.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SttResult.Failed, outcome(SttSessionCore.ERROR_CLIENT))
        assertEquals(SttResult.Text("milk"), outcome(SttSessionCore.ERROR_NO_MATCH, partial = "milk"))
    }

    @Test fun cancelReportsNothingAndFinishesOnce() {
        core.cancel(); core.cancel()
        core.onResults(listOf("late"))
        assertTrue(finals.isEmpty()); assertTrue(core.finished)
        assertEquals(1, finishedCalls)
    }
}
