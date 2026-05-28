package com.rokid.context.telemetry

data class TelemetryEvent(
    val name: String,
    val attrs: Map<String, String>,
)
