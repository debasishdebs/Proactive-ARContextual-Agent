package com.rokid.context.glasses.pause

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GlassesPausesTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private var n = 0
    private var job: Job? = null

    /** One DataStore per file at a time: the previous scope is closed first. */
    private fun pauses(name: String = "pauses.preferences_pb"): GlassesPauses = runBlocking {
        job?.cancelAndJoin()
        val j = SupervisorJob().also { job = it }
        GlassesPauses(PauseStore(File(tmp.root, name), CoroutineScope(Dispatchers.IO + j)) { now }, { now }, { "id${++n}" }, log = {})
    }
    @After fun tearDown() = runBlocking { job?.cancelAndJoin(); Unit }

    /** Review Focus 5: applied at once, persisted, and a restart never resumes capture. */
    @Test fun doubleTapPausesAtOnceAndSurvivesARestart() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        val t = p.toggle() as PauseToggle.Paused
        assertEquals(PauseRecord("id1", PauseOrigin.GLASSES, now, null), t.record)
        assertFalse(p.captureAllowed())
        val again = pauses().also { it.ensureLoaded() }
        assertFalse(again.captureAllowed())
    }

    @Test fun secondDoubleTapResumesOnlyTheKnownPauses() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(PauseRecord("phone1", PauseOrigin.PHONE, now, null)))
        assertEquals(setOf("phone1"), (p.toggle() as PauseToggle.Resumed).clears)
        assertTrue(p.captureAllowed())
    }

    @Test fun phoneRecordsAreMergedNeverRemoved() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.toggle()
        p.mergeFromPhone(emptyList())
        assertEquals(listOf("id1"), p.records.value.map { it.pauseId })
        p.mergeFromPhone(listOf(PauseRecord("phone1", PauseOrigin.PHONE, now, null)))
        assertEquals(setOf("id1", "phone1"), p.records.value.map { it.pauseId }.toSet())
    }

    @Test fun aTimedPauseExpiresOnTheGlassesClock() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(PauseRecord("t", PauseOrigin.PHONE, now, now + 1_800_000)))
        assertFalse(p.captureAllowed())
        now += 1_800_000
        assertTrue(p.captureAllowed())
    }

    /** Review Focus 5: an unreadable store is one indefinite pause the wearer can resume; afterwards the store works. */
    @Test fun unreadableStoreFailsClosed() = runBlocking {
        File(tmp.root, "bad.preferences_pb").writeText("not a protobuf")
        val p = pauses("bad.preferences_pb").also { it.ensureLoaded() }
        assertFalse(p.captureAllowed())
        assertEquals(setOf(PauseStore.UNREADABLE_ID), (p.toggle() as PauseToggle.Resumed).clears)
        assertTrue(p.captureAllowed())
        assertTrue("the repaired store reads back resumed", pauses("bad.preferences_pb").also { it.ensureLoaded() }.captureAllowed())
    }

    private val p1 = PauseRecord("p1", PauseOrigin.PHONE, 1_000_000, null)

    /** The wearer's Resume must not be undone by a phone push that still lists the cleared record. */
    @Test fun aStalePhonePushDoesNotRepauseAfterResume() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(p1))
        p.toggle()
        assertTrue(p.captureAllowed())
        p.mergeFromPhone(listOf(p1))
        assertTrue(p.captureAllowed())
        assertTrue(p.records.value.isEmpty())
    }

    @Test fun theTombstoneSurvivesARestart() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(p1)); p.toggle()
        val again = pauses().also { it.ensureLoaded() }
        again.mergeFromPhone(listOf(p1))
        assertTrue(again.captureAllowed())
    }

    @Test fun aPushWithoutTheIdDropsItsTombstone() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(p1)); p.toggle()
        p.mergeFromPhone(emptyList())
        p.mergeFromPhone(listOf(p1))
        assertFalse(p.captureAllowed())
    }

    @Test fun aNewPhonePauseIsNotBlockedByATombstone() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(p1)); p.toggle()
        p.mergeFromPhone(listOf(p1, PauseRecord("p2", PauseOrigin.PHONE, now, null)))
        assertFalse(p.captureAllowed())
        assertEquals(listOf("p2"), p.records.value.map { it.pauseId })
    }

    /**
     * An offline Resume stays pending (and is re-sent in every `pc_hello`) until the phone stops listing the id, however
     * long the link was down: a time limit would let a stale push re-pause capture with no user action.
     */
    @Test fun aTombstoneLastsUntilThePhoneDropsTheId() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(p1)); p.toggle()
        assertEquals(mapOf("p1" to now), p.resumedAtMs())
        now += 3_600_000
        p.mergeFromPhone(listOf(p1))
        assertTrue("an hour offline does not re-pause", p.captureAllowed())
        assertEquals(setOf("p1"), p.resumedAtMs().keys)
        val again = pauses().also { it.ensureLoaded() }
        assertEquals("persisted", setOf("p1"), again.resumedAtMs().keys)
        again.mergeFromPhone(emptyList())
        assertEquals("the phone dropped it: resolved", emptyMap<String, Long>(), again.resumedAtMs())
    }

    @Test fun nothingIsAllowedBeforeLoading() = assertFalse(pauses().captureAllowed())
}
