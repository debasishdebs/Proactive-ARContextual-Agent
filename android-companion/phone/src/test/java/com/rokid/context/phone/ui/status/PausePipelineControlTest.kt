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
        control(); advanceUntilIdle()
        assertEquals("no second migration", 1, hub.created.size)
    }

    /**
     * Fix round 1 I1: the migration holds the backlog. The legacy flag (which gates ingest, drain, embedding and so every
     * model call) stays off and resumePipeline is not called until the migrated pause is resumed; that Resume does both.
     */
    @Test fun migrationHoldsTheBacklogUntilTheMigratedPauseIsResumed() = runCtl {
        flag.enabled = false
        val c = control(); advanceUntilIdle()
        assertFalse(flag.enabled)
        assertEquals(0, flag.resumes)
        assertFalse(c.processingEnabled.value)
        val other = PauseRecord("g", PauseOrigin.GLASSES, 1, null)
        hub.active.value = hub.active.value + other
        c.resumeIds(setOf(other.pauseId)); advanceUntilIdle()
        assertEquals("resuming another pause doesn't release it", 0, flag.resumes)
        c.toggle(); advanceUntilIdle()
        assertTrue(flag.enabled)
        assertEquals(1, flag.resumes)
        assertTrue(c.processingEnabled.value)
    }

    @Test fun aMigratedPauseResumedBeforeARestartReleasesTheBacklogOnTheNextBuild() = runCtl {
        flag.enabled = false
        control(); advanceUntilIdle()
        hub.active.value = emptyList() // resumed elsewhere (e.g. the glasses) while this process was gone
        control(); advanceUntilIdle()
        assertEquals(1, flag.resumes)
    }

    /** I1: after a restart activePauses starts empty (not loaded yet); the stored held pause keeps the backlog held. */
    @Test fun anUnloadedActiveListDoesNotReleaseTheBacklog() = runCtl {
        flag.enabled = false; flag.migrated = true; flag.held = "h"
        hub.stored = listOf(PauseRecord("h", PauseOrigin.PHONE, 1, null))
        control(); advanceUntilIdle()
        assertEquals(0, flag.resumes)
        assertFalse(flag.enabled)
    }

    /** M2: capture pauses are capture-only; processing follows the legacy flag. */
    @Test fun processingFollowsTheLegacyFlagNotCapturePauses() = runCtl {
        val c = control(); advanceUntilIdle()
        c.toggle(); advanceUntilIdle()
        assertFalse(c.enabled.value)
        assertTrue(c.processingEnabled.value)
    }

    /** M3: after a process death the notification's Resume loads the stored pauses first, then resumes those ids. */
    @Test fun resumeLoadedNamesTheStoredIds() = runCtl {
        hub.stored = listOf(PauseRecord("s", PauseOrigin.PHONE, 1, null))
        val c = control(); advanceUntilIdle()
        c.resumeLoaded(); advanceUntilIdle()
        assertEquals(listOf(setOf("s")), hub.resumed)
    }

    @Test fun pauseUntilAndResumeIdsAreExact() = runCtl {
        hub.active.value = listOf(PauseRecord("a", PauseOrigin.GLASSES, 1, null), PauseRecord("b", PauseOrigin.GLASSES, 1, null))
        val c = control(); advanceUntilIdle()
        c.pauseUntil(now + 1_800_000); advanceUntilIdle()
        assertEquals(now + 1_800_000, hub.created.single().untilMs)
        c.resumeIds(setOf("a")); advanceUntilIdle()
        assertEquals(listOf(setOf("a")), hub.resumed)
        assertEquals(setOf("b", "phone1"), hub.active.value.map { it.pauseId }.toSet())
    }

    /** M2: the launch-time worker scheduling follows the processing flag, not capture pauses. */
    @Test fun mainActivitySchedulesWorkersOnTheProcessingFlag() {
        val src = java.io.File("src/main/java/com/rokid/context/MainActivity.kt").readText()
        assertTrue(src.contains("if (deps.pipeline.processingEnabled.value) {"))
        assertFalse(src.contains("deps.pipeline.enabled.value"))
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
    /** What the database holds (null = the same as [active]). */
    var stored: List<PauseRecord>? = null
    override suspend fun loadActive(): List<PauseRecord> = stored ?: active.value
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
    var held: String? = null
    var resumes = 0
    override fun migrated(): Boolean = migrated
    override fun markMigrated(heldPauseId: String?) { migrated = true; held = heldPauseId }
    override fun heldPauseId(): String? = held
    override fun pipelineEnabled(): Boolean = enabled
    override fun enablePipeline() { enabled = true; resumes++; held = null }
}
