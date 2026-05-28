package com.rokid.context.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryReporterTest {
    private val reporter = TelemetryReporter()

    @Test
    fun providerBlockedEmitsDurationAndReasonEvents() {
        val event = reporter.providerBlockedEvent(durationMs = 600_000L, reason = "no_provider")
        assertEquals("provider_blocked", event.name)
        assertEquals("600000", event.attrs["duration_ms"])
        assertEquals("no_provider", event.attrs["reason"])
    }

    @Test
    fun duplicateNotificationTextIsSuppressed() {
        assertFalse(reporter.shouldNotify("same", "same"))
        assertTrue(reporter.shouldNotify("same", "new"))
        assertTrue(reporter.shouldNotify(null, "first"))
    }
}
