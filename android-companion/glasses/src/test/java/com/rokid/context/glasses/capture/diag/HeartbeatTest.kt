package com.rokid.context.glasses.capture.diag

import com.rokid.context.glasses.capture.session.DiagStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private fun hb(stats: DiagStats = DiagStats(kf1m = 3, openMs = 6_000, spanMs = 60_000, analysed = 30, opens = 3, wakes = mapOf("timer" to 2, "dwell" to 1), glances = 6, micro = 20, novelSkips = 3, capped = 2, same = 1), up: Long = 12_345, upSpan: Long = 60_000, drain: Double? = 0.5, proj: Double? = 3.33, gap: Long = 20_000L, csvFail: Int = 0) = Heartbeat(
        running = true, reason = "sampling", uiMode = "HUD", wake = "MOTION", tier = "T1", targetH = 4, batt = 80, charging = false,
        drainPctPerMin = drain, projectedHours = proj, stats = stats, upBytes = up, upSpanMs = upSpan, heat = "NORMAL", batteryC = 31.5,
        wear = true, known = true, parked = false, sensors = "full", phoneUse = false, restartGapSec = 42, stillGapMs = gap,
        scrOn = 2, scrOurs = 0, nudgeOff = 1, csvFail = csvFail,
    )

    @Test fun fullSampleKeepsTheSpecKeyOrder() {
        assertEquals(
            "heartbeat v=2 running=true reason=sampling ui_mode=HUD wake=MOTION tier=T1 target_h=4 batt=80 chg=false drain=0.50 proj_h=3.3 " +
                "kf1m=3 camOpen=10% camOpenMs1m=6000 analysed1m=30 upBytes1m=12345 heat=NORMAL batteryC=31.5 wear=true known=true parked=false " +
                "sensors=full phoneUse=false restartGapSec=42 opens1m=3 wk=timer:2,dwell:1,onset:0,now:0,stream:0 stillGapMs=20000 glances1m=6 micro1m=20 novelSkip1m=3 capped1m=2 same1m=1 scrOn=2 scrOurs=0 nudgeOff=1 ctx=none ctxAgeS=na ctxApplied=UNKNOWN ctxVeto=0 plan=none gen=0 planMode=SETTINGS ruleSkip1m=0 exprErr=0 planRevert=none resv=0 shOpen=0 shSkip=0 worn=1 charging=0",
            hb().format(),
        )
    }

    @Test fun unknownDrainAndProjectionPrintNa() {
        val s = hb(drain = null, proj = null).format()
        assertTrue(s, s.contains(" drain=na proj_h=na "))
    }

    @Test fun countersAreNormalisedToAMinuteOverALongerSpan() {
        val s = hb(DiagStats(kf1m = 4, openMs = 12_000, spanMs = 120_000, analysed = 50), up = 60_000, upSpan = 120_000).format()
        assertTrue(s, s.contains(" kf1m=4 camOpen=10% camOpenMs1m=6000 analysed1m=25 upBytes1m=30000 "))
    }

    @Test fun aZeroSpanDoesNotDivideByZero() {
        val s = hb(DiagStats(0, 0, 0, 0)).format()
        assertTrue(s, s.contains(" kf1m=0 camOpen=0% camOpenMs1m=0 analysed1m=0 upBytes1m="))
    }

    @Test fun uplinkBytesUseTheirOwnSpanWithoutASession() {
        val s = hb(DiagStats(0, 0, 0, 0), up = 5_000, upSpan = 60_000).format()
        assertTrue(s, s.contains(" upBytes1m=5000 "))
    }

    @Test fun opensAreNormalisedToAMinute() {
        val s = hb(DiagStats(kf1m = 4, openMs = 12_000, spanMs = 120_000, analysed = 50, opens = 4)).format()
        assertTrue(s, s.contains(" opens1m=2 "))
    }

    @Test fun emptyWakesPrintZeros() {
        val s = hb(DiagStats(0, 0, 60_000, 0)).format()
        assertTrue(s, s.contains(" wk=timer:0,dwell:0,onset:0,now:0,stream:0 "))
    }

    @Test fun glanceCountersAreNormalisedToAMinute() {
        val s = hb(DiagStats(0, 0, 120_000, 0, glances = 6)).format()
        assertTrue(s, s.contains(" glances1m=3 "))
    }

    @Test fun defaultsPrintZeros() {
        val s = Heartbeat(
            running = true, reason = "r", uiMode = null, wake = "w", tier = "T1", targetH = 4, batt = 1, charging = false, drainPctPerMin = null,
            projectedHours = null, stats = DiagStats(0, 0, 60_000, 0), upBytes = 0, heat = "NORMAL", batteryC = null, wear = true, known = true,
            parked = false, sensors = "full", phoneUse = false, restartGapSec = null,
        ).format()
        assertTrue(s, s.endsWith(" stillGapMs=0 glances1m=0 micro1m=0 novelSkip1m=0 capped1m=0 same1m=0 scrOn=0 scrOurs=0 nudgeOff=0 ctx=none ctxAgeS=na ctxApplied=UNKNOWN ctxVeto=0 plan=none gen=0 planMode=SETTINGS ruleSkip1m=0 exprErr=0 planRevert=none resv=0 shOpen=0 shSkip=0 worn=1 charging=0"))
    }

    @Test fun csvFailuresAreAppendedOnlyWhenNonZero() {
        assertTrue(hb(csvFail = 2).format().endsWith(" charging=0 csvFail=2"))
        assertTrue(!hb().format().contains("csvFail"))
    }

    @Test fun contextKeysFollowNudgeOffInOrder() {
        val s = hb().copy(ctx = "REST", ctxAgeS = 42, ctxApplied = "UNKNOWN", ctxVeto = 3).format()
        assertTrue(s, s.contains(" nudgeOff=1 ctx=REST ctxAgeS=42 ctxApplied=UNKNOWN ctxVeto=3 plan=none "))
    }

    @Test fun planFieldsFollowTheContextKeysInOrder() {
        val h = hb(DiagStats(kf1m = 0, openMs = 0, spanMs = 60_000, analysed = 0, wakes = mapOf("resv" to 3))).copy(
            plan = "ab12cd34", gen = 7, planMode = "TRIAL", planRevert = "auto:ceiling", shOpen = 5, shSkip = 2, wear = false, charging = true,
        )
        assertTrue(h.format(), h.format().endsWith(" ctxVeto=0 plan=ab12cd34 gen=7 planMode=TRIAL ruleSkip1m=0 exprErr=0 planRevert=auto:ceiling resv=3 shOpen=5 shSkip=2 worn=0 charging=1"))
    }

    @Test fun reservedAttemptsComeFromTheSessionWakeCounts() {
        assertTrue(hb().format().contains(" resv=0 "))
        assertTrue("resv has its own key, wk keeps its five", hb(DiagStats(0, 0, 60_000, 0, wakes = mapOf("timer" to 1, "resv" to 2))).format().contains(" wk=timer:1,dwell:0,onset:0,now:0,stream:0 "))
    }

    /** A19: no sampling session means the sensors are off, not "full". */
    @Test fun sensorsOffWithoutSession() {
        assertEquals("off", Heartbeat.sensorsLabel(hasSession = false, rate = "low"))
        assertEquals("low", Heartbeat.sensorsLabel(hasSession = true, rate = "low"))
        assertEquals("full", Heartbeat.sensorsLabel(hasSession = true, rate = null))
    }

    /** A20: a throw while building the heartbeat is logged by class name only and returns null, so the caller still publishes. */
    @Test fun heartbeatThrowStillPublishesStatus() {
        val logs = ArrayList<String>()
        assertEquals(null, Heartbeat.guarded({ logs += it }) { throw IllegalStateException("secret text here") })
        assertEquals(listOf("heartbeat failed: IllegalStateException"), logs)
        assertEquals("ok", Heartbeat.guarded({ logs += it }) { "ok" })
        assertEquals(1, logs.size)
    }
}
