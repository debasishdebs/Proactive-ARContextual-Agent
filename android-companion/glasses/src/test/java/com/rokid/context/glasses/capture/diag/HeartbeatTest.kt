package com.rokid.context.glasses.capture.diag

import com.rokid.context.glasses.capture.session.DiagStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatTest {
    private fun hb(stats: DiagStats = DiagStats(kf1m = 3, openMs = 6_000, spanMs = 60_000, analysed = 30), up: Long = 12_345, upSpan: Long = 60_000, drain: Double? = 0.5, proj: Double? = 3.33) = Heartbeat(
        running = true, reason = "sampling", uiMode = "HUD", wake = "MOTION", tier = "T1", targetH = 4, batt = 80, charging = false,
        drainPctPerMin = drain, projectedHours = proj, stats = stats, upBytes = up, upSpanMs = upSpan, heat = "NORMAL", batteryC = 31.5,
        wear = true, known = true, parked = false, sensors = "full", phoneUse = false, restartGapSec = 42,
    )

    @Test fun fullSampleKeepsTheSpecKeyOrder() {
        assertEquals(
            "heartbeat v=2 running=true reason=sampling ui_mode=HUD wake=MOTION tier=T1 target_h=4 batt=80 chg=false drain=0.50 proj_h=3.3 " +
                "kf1m=3 camOpen=10% camOpenMs1m=6000 analysed1m=30 upBytes1m=12345 heat=NORMAL batteryC=31.5 wear=true known=true parked=false " +
                "sensors=full phoneUse=false restartGapSec=42",
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
}
