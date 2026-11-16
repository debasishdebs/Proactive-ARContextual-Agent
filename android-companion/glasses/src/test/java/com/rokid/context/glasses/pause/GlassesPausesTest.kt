package com.rokid.context.glasses.pause

import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.QuietHours
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
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
import androidx.datastore.preferences.core.edit
import java.io.File

/** Phase 6 tests: a clock-exact push of [records] (the old list-only merge). */
private suspend fun GlassesPauses.mergeFromPhone(records: List<PauseRecord>) =
    mergeFromPhone(HudSettings(pauses = records, pausesExact = true))

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

    // ---- Phase 7 A2: phone resumes, exact/provisional merge, quiet hours ----

    private var hud: HudSettings? = null
    private fun qPauses(name: String = "q.preferences_pb"): GlassesPauses = runBlocking {
        job?.cancelAndJoin()
        val j = SupervisorJob().also { job = it }
        GlassesPauses(PauseStore(File(tmp.root, name), CoroutineScope(Dispatchers.IO + j)) { now }, { now }, { "id${++n}" }, log = {},
            quiet = QuietHoursClock { hud })
    }
    private fun exact(vararg r: PauseRecord, resumed: List<String> = emptyList()) =
        HudSettings(pauses = r.toList(), pausesExact = true, resumedPauseIds = resumed)
    private fun inexact(vararg r: PauseRecord) = HudSettings(pauses = r.toList(), pausesExact = false)

    @Test fun phoneResumedIdRemovesPhoneRecordAndIsNeverReadded() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        val rec = PauseRecord("p", PauseOrigin.PHONE, now, null)
        p.mergeFromPhone(exact(rec))
        assertFalse(p.captureAllowed())
        val r = p.mergeFromPhone(exact(resumed = listOf("p")))
        assertEquals(setOf("p"), r.removed)
        assertTrue(p.captureAllowed())
        p.mergeFromPhone(exact(rec))
        p.mergeFromPhone(inexact(rec))
        assertTrue("never re-added", p.captureAllowed())
        assertTrue("also after a restart", pauses().also { it.ensureLoaded(); it.mergeFromPhone(exact(rec)) }.captureAllowed())
    }

    @Test fun exactPushMergesMostRestrictive() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        val ten = now + 600_000; val eleven = now + 1_200_000; val nine = now + 300_000
        p.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, ten)))
        p.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, eleven)))
        assertEquals(listOf(eleven), p.records.value.map { it.untilMs })
        p.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, nine)))
        assertEquals(listOf(eleven), p.records.value.map { it.untilMs })
    }

    @Test fun inexactTimedPhoneRecordIsIndefiniteUntilExact() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        val ten = now + 600_000
        val r = p.mergeFromPhone(inexact(PauseRecord("p", PauseOrigin.PHONE, now, ten)))
        assertEquals(listOf<Long?>(null), r.added.map { it.untilMs })
        assertEquals(listOf<Long?>(null), p.records.value.map { it.untilMs })
        assertEquals(emptyList<PauseRecord>(), p.helloRecords())
        val nineFiftyEight = ten - 120_000
        p.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, nineFiftyEight)))
        assertEquals(listOf<Long?>(nineFiftyEight), p.records.value.map { it.untilMs })
        assertEquals("no longer provisional", listOf("p"), p.helloRecords().map { it.pauseId })
        p.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, nineFiftyEight - 60_000)))
        assertEquals(listOf<Long?>(nineFiftyEight), p.records.value.map { it.untilMs })
    }

    @Test fun provisionalSurvivesARestart() = runBlocking {
        pauses().also { it.ensureLoaded() }.mergeFromPhone(inexact(PauseRecord("p", PauseOrigin.PHONE, now, now + 600_000)))
        val again = pauses().also { it.ensureLoaded() }
        again.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, now + 60_000)))
        assertEquals(listOf<Long?>(now + 60_000), again.records.value.map { it.untilMs })
    }

    @Test fun inexactPushNeverNarrowsKnownId() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(exact(PauseRecord("p", PauseOrigin.PHONE, now, null)))
        p.mergeFromPhone(inexact(PauseRecord("p", PauseOrigin.PHONE, now, now + 1_000)))
        now += 5_000
        assertEquals(listOf<Long?>(null), p.records.value.map { it.untilMs })
        assertFalse(p.captureAllowed())
    }

    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun quietHoursOfflineCreatesUniqueIdsPerNight() = runBlocking {
        val z = ZoneId.of("Europe/Berlin")
        hud = HudSettings(quietHours = QuietHours(22 * 60, 7 * 60), zoneId = z.id)
        val p = qPauses().also { it.ensureLoaded() }
        now = ms(z, 2026, 10, 13, 23, 0)
        assertFalse(p.captureAllowed())
        p.ensureLoaded()
        val first = p.active().single()
        now = ms(z, 2026, 10, 14, 7, 0)
        assertTrue(p.captureAllowed())
        now = ms(z, 2026, 10, 14, 22, 30)
        p.ensureLoaded()
        val second = p.active().single()
        assertEquals(PauseOrigin.QUIET_HOURS, second.origin)
        assertEquals(ms(z, 2026, 10, 14, 7, 0), first.untilMs)
        assertEquals(ms(z, 2026, 10, 15, 7, 0), second.untilMs)
        assertTrue(first.pauseId != second.pauseId)
    }

    @Test fun quietHoursUseSentZone() = runBlocking {
        val old = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val k = ZoneId.of("Asia/Kolkata")
            hud = HudSettings(quietHours = QuietHours(22 * 60, 7 * 60), zoneId = k.id)
            val clock = QuietHoursClock { hud }
            val r = clock.occurrenceAt(ms(k, 2026, 10, 13, 23, 0))!!
            assertEquals(ms(k, 2026, 10, 13, 22, 0), r.first)
            assertEquals(ms(k, 2026, 10, 14, 7, 0), r.last + 1)
            // 23:00 UTC is 04:30 Kolkata: inside; 12:00 UTC is 17:30 Kolkata: outside
            assertTrue(clock.occurrenceAt(ms(ZoneId.of("UTC"), 2026, 10, 13, 23, 0)) != null)
            assertEquals(null, clock.occurrenceAt(ms(ZoneId.of("UTC"), 2026, 10, 13, 12, 0)))
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test fun localResumeInsideQuietWindowStaysResumed() = runBlocking {
        val z = ZoneId.of("Europe/Berlin")
        hud = HudSettings(quietHours = QuietHours(22 * 60, 7 * 60), zoneId = z.id)
        val p = qPauses().also { it.ensureLoaded() }
        now = ms(z, 2026, 10, 13, 23, 0)
        assertTrue(p.toggle() is PauseToggle.Resumed)
        assertTrue(p.captureAllowed())
        now += 3_600_000
        p.ensureLoaded()
        assertTrue(p.captureAllowed())
        assertTrue("also after a restart", qPauses().also { it.ensureLoaded() }.captureAllowed())
    }

    @Test fun aLiveQuietRecordFromThePhoneIsAdoptedNotDuplicated() = runBlocking {
        val z = ZoneId.of("Europe/Berlin")
        hud = HudSettings(quietHours = QuietHours(22 * 60, 7 * 60), zoneId = z.id)
        val p = qPauses().also { it.ensureLoaded() }
        now = ms(z, 2026, 10, 13, 21, 0)
        p.ensureLoaded()
        p.mergeFromPhone(exact(PauseRecord("phq", PauseOrigin.QUIET_HOURS, ms(z, 2026, 10, 13, 22, 0), ms(z, 2026, 10, 14, 7, 0))))
        now = ms(z, 2026, 10, 13, 23, 0)
        p.ensureLoaded()
        assertEquals(listOf("phq"), p.active().map { it.pauseId })
        p.mergeFromPhone(exact(resumed = listOf("phq")))
        assertTrue("the phone's resume of the occurrence holds", p.captureAllowed())
    }

    /** Ruling: a provisional record the phone no longer lists in an exact push has ended there (expired or resumed). */
    @Test fun exactPushWithoutAProvisionalIdEndsIt() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(exact(PauseRecord("known", PauseOrigin.PHONE, now, null)))
        p.mergeFromPhone(inexact(PauseRecord("p", PauseOrigin.PHONE, now, now + 600_000)))
        assertTrue(p.mergeFromPhone(inexact()).removed.isEmpty())
        assertEquals(setOf("known", "p"), p.active().map { it.pauseId }.toSet())
        assertEquals(setOf("p"), p.mergeFromPhone(exact()).removed)
        assertEquals("a non-provisional record is never dropped this way", listOf("known"), p.active().map { it.pauseId })
    }

    /** Fix round 1 (M12): out-of-range minutes from a bad push or file never crash the glasses; they are no window. */
    @Test fun outOfRangeQuietMinutesNeverCrash() = runBlocking {
        hud = HudSettings(quietHours = QuietHours(22 * 60, 1_440), zoneId = "UTC")
        val p = qPauses().also { it.ensureLoaded() }
        assertTrue(p.captureAllowed())
        assertEquals(null, QuietHoursClock { HudSettings(quietHours = QuietHours(-1, 60)) }.occurrenceAt(now))
    }
}

/** 2026-10-25 wear window: capture paused twice and no log line said who. The source of every pause is now a word in the logs (log text only, no wire change). */
class PauseSourceTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000_000L
    private var n = 0
    private var job: Job? = null
    private val lines = ArrayList<String>()

    private fun pauses(name: String = "pauses.preferences_pb"): GlassesPauses = runBlocking {
        job?.cancelAndJoin()
        val j = SupervisorJob().also { job = it }
        GlassesPauses(PauseStore(File(tmp.root, name), CoroutineScope(Dispatchers.IO + j)) { now }, { now }, { "id${++n}" }, log = { lines += it })
    }
    @After fun tearDown() = runBlocking { job?.cancelAndJoin(); Unit }

    @Test fun beforeTheStoreIsLoadedTheSourceSaysSo() {
        assertEquals("NOT_LOADED", pauses().sourceLabel())
    }

    @Test fun noPauseIsNone() = runBlocking {
        assertEquals("none", pauses().also { it.ensureLoaded() }.sourceLabel())
    }

    @Test fun aDoubleTapIsUserGlassesAndAfterARestartItIsRestored() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.toggle()
        assertEquals("USER_GLASSES", p.sourceLabel())
        assertTrue(lines.toString(), lines.any { it.startsWith("pause added src=USER_GLASSES") })
        val again = pauses().also { it.ensureLoaded() }
        assertEquals("USER_GLASSES/restored", again.sourceLabel())
        assertTrue(lines.toString(), lines.any { it.startsWith("pause loaded active=1 src=USER_GLASSES/restored") })
    }

    @Test fun aPhonePauseIsUserPhoneAndAQuietHoursRecordIsQuietHours() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.mergeFromPhone(HudSettings(pauses = listOf(PauseRecord("ph", PauseOrigin.PHONE, now, null), PauseRecord("qh", PauseOrigin.QUIET_HOURS, now, now + 60_000)), pausesExact = true))
        assertEquals("QUIET_HOURS+USER_PHONE", p.sourceLabel())
        assertTrue(lines.toString(), lines.any { it.startsWith("pause phone push added=2 removed=0") })
    }

    /** A store written before the source was kept: every record reads as PERSISTED (backwards compatible). */
    @Test fun anOldRecordWithoutASourceReadsAsPersisted() = runBlocking {
        val j = SupervisorJob()
        val f = File(tmp.root, "old.preferences_pb")
        // A pre-source file: only the "pauses" key, exactly what the older build wrote.
        androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + j), produceFile = { f }).edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("pauses")] = """[{"pauseId":"old1","origin":"PHONE","createdAtMs":5}]"""
        }
        j.cancelAndJoin()
        assertEquals("PERSISTED/restored", pauses("old.preferences_pb").also { it.ensureLoaded() }.sourceLabel())
    }

    @Test fun anUnreadableStoreNamesItself() = runBlocking {
        File(tmp.root, "bad.preferences_pb").writeText("not a datastore file")
        assertEquals("STORE_UNREADABLE/restored", pauses("bad.preferences_pb").also { it.ensureLoaded() }.sourceLabel())
    }

    @Test fun theResumeNamesWhatItCleared() = runBlocking {
        val p = pauses().also { it.ensureLoaded() }
        p.toggle(); lines.clear(); p.toggle()
        assertTrue(lines.toString(), lines.any { it.startsWith("pause resumed n=1 src=USER_GLASSES") })
        assertEquals("none", p.sourceLabel())
    }

    @Test fun theStatusReasonMapsToASource() {
        assertEquals("USER_PHONE", PauseSource.forReason("paused", "USER_PHONE"))
        assertEquals("BATTERY_LOW", PauseSource.forReason("battery", "none"))
        assertEquals("BATTERY_BUDGET", PauseSource.forReason("battery_budget", "none"))
        assertEquals("-", PauseSource.forReason("not_worn", "none"))
        assertEquals("-", PauseSource.forReason(null, "none"))
    }

    // ---- Pause zones: `zone:` records are the phone's; a double-tap never clears them, and the approach flag holds capture while the link is down. ----

    private val zoneRec = PauseRecord("zone:jarawa:1", PauseOrigin.PHONE, 1_000_000, null)
    private var approach = false
    private var linked = true

    private fun zonePauses(name: String = "zone.preferences_pb"): GlassesPauses = runBlocking {
        job?.cancelAndJoin()
        val j = SupervisorJob().also { job = it }
        GlassesPauses(PauseStore(File(tmp.root, name), CoroutineScope(Dispatchers.IO + j)) { now }, { now }, { "id${++n}" }, log = {}, zoneApproach = { approach }, linkUp = { linked })
    }

    @Test fun aDoubleTapNeverClearsAZoneRecord() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }
        p.mergeFromPhone(listOf(zoneRec))
        assertFalse(p.captureAllowed())
        assertEquals(PauseToggle.ZoneLocked, p.toggle())
        assertEquals(PauseToggle.ZoneLocked, p.toggle())
        assertFalse(p.captureAllowed()); assertEquals(listOf("zone:jarawa:1"), p.records.value.map { it.pauseId })
        assertTrue(p.resumedAtMs().isEmpty()); assertTrue("no pause was added by the tap", p.records.value.size == 1)
    }

    @Test fun aDoubleTapClearsTheWearersOwnPausesButTheZoneStaysAndSaysSo() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }
        val own = (p.toggle() as PauseToggle.Paused).record // the wearer pauses first
        p.mergeFromPhone(listOf(zoneRec, PauseRecord("phone1", PauseOrigin.PHONE, now, null)))
        val t = p.toggle() as PauseToggle.Resumed
        assertEquals(setOf(own.pauseId, "phone1"), t.clears); assertTrue(t.zoneHeld)
        assertFalse("the zone still holds capture", p.captureAllowed())
        assertEquals(listOf("zone:jarawa:1"), p.records.value.map { it.pauseId })
    }

    @Test fun withoutAZoneARestingToggleStillResumesLikeBefore() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }
        p.toggle(); val t = p.toggle() as PauseToggle.Resumed
        assertFalse(t.zoneHeld); assertTrue(p.captureAllowed())
    }

    @Test fun aZoneRecordSurvivesARestartAndTheTap() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }; p.mergeFromPhone(listOf(zoneRec)); p.toggle()
        val again = zonePauses().also { it.ensureLoaded() }
        assertFalse(again.captureAllowed()); assertEquals(PauseToggle.ZoneLocked, again.toggle())
    }

    @Test fun thePhoneEndsAZoneRecordByNameOnly() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }; p.mergeFromPhone(listOf(zoneRec))
        p.mergeFromPhone(HudSettings(pauses = emptyList(), pausesExact = true, resumedPauseIds = listOf("zone:jarawa:1")))
        assertTrue(p.captureAllowed())
    }

    @Test fun zoneActiveFollowsZoneRecordsAndTheApproachLinkHold() = runBlocking {
        val p = zonePauses()
        assertTrue("not loaded: fail closed", p.zoneActive())
        p.ensureLoaded(); assertFalse(p.zoneActive())
        p.toggle(); assertFalse("an ordinary pause is not a zone", p.zoneActive())
        p.toggle(); p.mergeFromPhone(listOf(zoneRec)); assertTrue(p.zoneActive())
        p.mergeFromPhone(HudSettings(resumedPauseIds = listOf("zone:jarawa:1"), pausesExact = true)); assertFalse(p.zoneActive())
        approach = true; linked = true; assertFalse(p.zoneActive())
        linked = false; assertTrue(p.zoneActive())
    }

    @Test fun approachWithTheLinkDownPausesLocallyAndLiftsWhenTheLinkReturnsOrTheFlagClears() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }
        assertTrue(p.captureAllowed())
        approach = true; assertTrue("link up: the phone decides", p.captureAllowed())
        linked = false; assertFalse(p.captureAllowed()); assertEquals("ZONE_LINK_DOWN", p.sourceLabel())
        linked = true; assertTrue(p.captureAllowed())
        linked = false; approach = false; assertTrue("not approaching: a dropped link is normal", p.captureAllowed()); assertEquals("none", p.sourceLabel())
    }

    @Test fun zoneRecordsLabelAsZone() = runBlocking {
        val p = zonePauses().also { it.ensureLoaded() }; p.mergeFromPhone(listOf(zoneRec))
        assertEquals("ZONE", p.sourceLabel())
    }
}
