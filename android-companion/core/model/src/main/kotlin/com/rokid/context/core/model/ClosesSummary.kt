package com.rokid.context.core.model

/** The Diagnostics wording "Rokid closed the app N times (last gap Xs; M unverified; K other force-stops)", shared by the glasses log and the phone screen. Null when nothing was counted. */
fun closesSummary(rokid: Int, lastGapSec: Int?, unverified: Int, other: Int): String? {
    if (rokid <= 0 && unverified <= 0 && other <= 0) return null
    val parts = listOfNotNull(
        if (rokid > 0) "last gap ${lastGapSec ?: 0}s" else null,
        if (unverified > 0) "$unverified unverified" else null,
        if (other > 0) "$other other force-stop${if (other == 1) "" else "s"}" else null,
    )
    return "Rokid closed the app $rokid time${if (rokid == 1) "" else "s"}" + if (parts.isEmpty()) "" else " (${parts.joinToString("; ")})"
}
