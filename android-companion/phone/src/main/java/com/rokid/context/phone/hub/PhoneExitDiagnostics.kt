package com.rokid.context.phone.hub

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.rokid.context.core.log.PcLog

/** One ApplicationExitInfo of this app's own process reduced to numbers. There is no description field on purpose: it can carry other apps' names. */
data class ExitFacts(val reason: Int, val status: Int, val importance: Int, val pssKb: Long, val rssKb: Long, val timestampMs: Long)

/**
 * v1.1 T1 (hub-restart design section 3): at every cold process start ONE `PcExit` line says how the previous processes of the phone app ended, so a
 * hub that vanished (15 Nov 22:15) can be explained next time. `exempt` is the battery-optimisation exemption (recovery from a process start needs it
 * on Android 12+); `hubLive` stays `n/a` until the hub-live flag exists (T3). Numbers and flags only, never the exit description.
 */
object PhoneExitDiagnostics {
    const val TAG = "PcExit"
    const val RECORDS = 3

    /** `n=2 [reason=10 status=0 imp=100 pssKB=1 rssKB=2 ago=40s] [...] exempt=y hubLive=n/a`, newest first; ago is relative to [nowMs]. */
    fun format(records: List<ExitFacts>, nowMs: Long, exempt: Boolean, hubLive: String = "n/a"): String =
        "n=${records.size}" + records.joinToString("") {
            " [reason=${it.reason} status=${it.status} imp=${it.importance} pssKB=${it.pssKb} rssKB=${it.rssKb} ago=${(nowMs - it.timestampMs).coerceAtLeast(0L) / 1_000L}s]"
        } + " exempt=${if (exempt) "y" else "n"} hubLive=$hubLive"

    /** Reads the exit records (API 30+; none before) and logs the line. Off the main thread (the platform call is a binder round trip); never throws. */
    fun logAtStart(context: Context) {
        runCatching {
            val records = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) emptyList() else runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, RECORDS)
                    .map { ExitFacts(it.reason, it.status, it.importance, it.pss, it.rss, it.timestamp) }
            }.getOrDefault(emptyList())
            PcLog.milestone(TAG, format(records, System.currentTimeMillis(), AutoStopWake.exempt(context)))
        }
    }
}
