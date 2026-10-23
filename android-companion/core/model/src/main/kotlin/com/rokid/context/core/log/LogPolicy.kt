package com.rokid.context.core.log

/**
 * The single routing decision. [PcLog] is the only caller.
 * logcat = level >= I || diag (I/W/E always visible to the in-app Logs screen, D only while diagnostics are on).
 * file   = diag || level >= W (a failure with no record is the defect being fixed; I and D only while diagnostics are on).
 */
object LogPolicy {
    fun route(level: Level, tag: String, diag: Boolean): Route =
        Route(logcat = diag || level >= Level.I, file = diag || level >= Level.W)
}
