package com.rokid.context.core.log

/**
 * The single routing decision. [PcLog] is the only caller.
 * logcat = level >= I || diag (I/W/E always visible to the in-app Logs screen, D only while diagnostics are on).
 * file   = diag || level >= W || milestone (a failure with no record is the defect being fixed; I and D only while diagnostics are on).
 * A [milestone] is a rare, explicit INFO line ([PcLog.milestone]: a state change someone must be able to read after the fact, e.g. the memory-index lifecycle):
 * it reaches the file whether diagnostics are on or not, and is logcat-visible like any I line. It is never a warning and never raises a level.
 */
object LogPolicy {
    fun route(level: Level, tag: String, diag: Boolean, milestone: Boolean = false): Route =
        Route(logcat = diag || level >= Level.I, file = diag || level >= Level.W || (milestone && level >= Level.I))
}
