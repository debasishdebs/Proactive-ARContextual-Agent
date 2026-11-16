package com.rokid.context.phone.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** v1.1 T1: the PcExit line is numbers and flags only. */
class PhoneExitDiagnosticsTest {
    @Test fun formatsTheRecordsNewestFirstWithExemptAndHubLive() {
        val now = 1_000_000L
        val line = PhoneExitDiagnostics.format(
            listOf(ExitFacts(reason = 10, status = 0, importance = 100, pssKb = 2048, rssKb = 4096, timestampMs = now - 40_000), ExitFacts(4, 1, 400, 0, 0, now - 90_000)),
            now, exempt = false,
        )
        assertEquals("n=2 [reason=10 status=0 imp=100 pssKB=2048 rssKB=4096 ago=40s] [reason=4 status=1 imp=400 pssKB=0 rssKB=0 ago=90s] exempt=n hubLive=n/a", line)
    }

    @Test fun noRecordsAndAFutureTimestampStayWellFormed() {
        assertEquals("n=0 exempt=y hubLive=n/a", PhoneExitDiagnostics.format(emptyList(), 5L, exempt = true))
        assertEquals("n=1 [reason=1 status=0 imp=0 pssKB=0 rssKB=0 ago=0s] exempt=y hubLive=y", PhoneExitDiagnostics.format(listOf(ExitFacts(1, 0, 0, 0, 0, 99L)), 5L, true, "y"))
    }

    @Test fun thereIsNoPlaceForADescription() {
        // ExitFacts has numeric fields only, so no description text can reach the line.
        assertFalse(ExitFacts::class.java.declaredFields.any { it.type == String::class.java })
    }
}
