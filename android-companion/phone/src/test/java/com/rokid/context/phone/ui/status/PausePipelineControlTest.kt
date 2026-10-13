package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausePipelineControlTest {
    private var now = 1_000L
    private val hub = FakePauseHub { now }
    private val flag = FakeLegacyFlag()
    private val ctl = Job()
    /** On the test scheduler (advanceUntilIdle runs it); the control's loop is cancelled when the test ends. */
    private fun TestScope.control(): PausePipelineControl = PausePipelineControl(
        hub, CoroutineScope(coroutineContext + ctl), { now }, flag,
        io = coroutineContext[ContinuationInterceptor] as CoroutineDispatcher,
    )
    private fun runCtl(block: suspend TestScope.() -> Unit) = runTest { try { block() } finally { ctl.cancel() } }

    @Test fun toggleWhileRunningCreatesPhonePause() = runCtl {
        val c = control(); advanceUntilIdle()
        assertTrue(c.enabled.value)
        c.toggle(); advanceUntilIdle()
        val r = hub.active.value.single()
        assertEquals(PauseOrigin.PHONE, r.origin)
        assertNull(r.untilMs)
        assertFalse(c.enabled.value)
    }

    @Test fun resumeNamesOnlyShownIds() = runCtl {
        hub.active.value = listOf(PauseRecord("a", PauseOrigin.GLASSES, 1, null))
        val c = control(); advanceUntilIdle()
        c.toggle() // shows {a}
        hub.active.value = hub.active.value + PauseRecord("b", PauseOrigin.GLASSES, 2, null) // a hello lands before the IO
        advanceUntilIdle()
        assertEquals(listOf(setOf("a")), hub.resumed)
        assertEquals(listOf("b"), hub.active.value.map { it.pauseId })
        assertEquals(now, c.lastResumeAtMs.value)
    }

    @Test fun quickPauseThenResumeResumesThePauseItMade() = runCtl {
        val c = control(); advanceUntilIdle()
        c.toggle(); c.toggle(); advanceUntilIdle()
        assertTrue("the second tap resumes, it does not pause again", hub.active.value.isEmpty())
        assertEquals(1, hub.created.size)
    }

    @Test fun legacyFalseFlagMigratesOnce() = runCtl {
        flag.enabled = false
        control(); advanceUntilIdle()
        assertEquals(1, hub.created.size)
        assertEquals(PauseOrigin.PHONE, hub.created.single().origin)
        assertNull(hub.created.single().untilMs)
        assertTrue(flag.enabled)
        flag.enabled = false // e.g. the legacy Config screen switched it off again: no second migration
        control(); advanceUntilIdle()
        assertEquals(1, hub.created.size)
    }

    @Test fun legacyTrueFlagOnlyMarks() = runCtl {
        control(); advanceUntilIdle()
        assertTrue(hub.created.isEmpty())
        assertTrue(flag.migrated)
    }
}

class FakePauseHub(private val clock: () -> Long) : PauseHub {
    val active = MutableStateFlow<List<PauseRecord>>(emptyList())
    override val activePauses: StateFlow<List<PauseRecord>> = active
    val created = mutableListOf<PauseRecord>()
    val resumed = mutableListOf<Set<String>>()
    private var n = 0
    override suspend fun pausePhone(untilMs: Long?): PauseRecord =
        PauseRecord("phone${++n}", PauseOrigin.PHONE, clock(), untilMs).also { created += it; active.value = active.value + it }
    override suspend fun resume(ids: Set<String>) {
        resumed += ids
        active.value = active.value.filter { it.pauseId !in ids }
    }
}

class FakeLegacyFlag : LegacyFlag {
    var enabled = true
    var migrated = false
    override fun migrated(): Boolean = migrated
    override fun markMigrated() { migrated = true }
    override fun pipelineEnabled(): Boolean = enabled
    override fun enablePipeline() { enabled = true }
}
