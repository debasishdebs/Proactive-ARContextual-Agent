package com.rokid.context.ui.diagnostics

object DiagnosticsScreen {
    fun verboseLogsRetentionHours(enabled: Boolean): Int = if (enabled) 72 else 0
}
