package com.rokid.context.telemetry

class TelemetryReporter {
    fun providerBlockedEvent(durationMs: Long, reason: String): TelemetryEvent =
        TelemetryEvent(
            name = "provider_blocked",
            attrs = mapOf(
                "duration_ms" to durationMs.toString(),
                "reason" to reason,
            ),
        )

    fun shouldNotify(lastText: String?, newText: String): Boolean = lastText != newText
}
