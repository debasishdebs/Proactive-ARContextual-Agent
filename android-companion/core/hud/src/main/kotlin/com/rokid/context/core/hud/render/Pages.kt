package com.rokid.context.core.hud.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhonelinkOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokid.context.core.hud.Glyph
import com.rokid.context.core.hud.Hud
import com.rokid.context.core.hud.Label
import com.rokid.context.core.hud.OutlinedBox
import com.rokid.context.core.model.Health

/** Silent verbosity shows only this (spec §3.4): REC dot, or ❚❚ while paused. */
@Composable
fun RecDot(m: HudModel) {
    if (m.paused) Glyph(Icons.Outlined.Pause, 24.dp, Hud.PRIMARY) else Glyph(Icons.Outlined.FiberManualRecord, 18.dp, Hud.PRIMARY)
}

/** Status row (spec §3.2): REC / ❚❚, phone link (offline per §3.6), battery. */
@Composable
fun StatusRow(m: HudModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RecDot(m)
        Label(if (m.paused) "PAUSED" else "REC", 20.sp, Hud.SECONDARY)
        when (m.connection) {
            HudConnection.LIVE -> Glyph(Icons.Outlined.PhoneAndroid, 22.dp, Hud.SECONDARY)
            HudConnection.CONNECTING -> Glyph(Icons.Outlined.PhoneAndroid, 22.dp, Hud.TERTIARY)
            HudConnection.OFFLINE, HudConnection.OUTDATED -> {
                Glyph(Icons.Outlined.PhonelinkOff, 22.dp, Hud.SECONDARY)
                Label("offline", 20.sp, Hud.SECONDARY)
            }
        }
        val battery = m.batteryPct
        if (battery != null) {
            Glyph(Icons.Outlined.BatteryStd, 22.dp, Hud.TERTIARY)
            Label("$battery%", 20.sp, Hud.SECONDARY)
        }
    }
}

/** Glance (default page): status row, NEXT countdown in an outlined box, latest nudge one-liner. */
@Composable
fun GlancePage(m: HudModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusRow(m)
        OutlinedBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Label(if (m.paused) "PAUSED" else "NEXT", 20.sp, Hud.SECONDARY)
                if (m.paused) Glyph(Icons.Outlined.Pause, 56.dp, Hud.PRIMARY)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(formatCountdown(m.nextCaptureAtMs, m.nowMs), 56.sp, Hud.PRIMARY, FontWeight.Bold)
                    if (m.photoCue) Label(HudCopy.PHOTO_CUE, 36.sp, Hud.PRIMARY, FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        val nudge = m.frame?.latestNudge
        if (nudge != null) Label(nudge.text, 22.sp, Hud.SECONDARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Today: recent memories, time at 60 %, text at 100 %, outlined highlight on the selected row. */
@Composable
fun TodayPage(m: HudModel) {
    val rows = m.frame?.today.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("TODAY", 20.sp, Hud.SECONDARY)
        if (rows.isEmpty()) Label("Nothing yet today", 22.sp, Hud.SECONDARY)
        for (i in visibleRows(rows.size, m.nav.todayRow, rows = 4)) {
            val r = rows[i]
            val line: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(formatClock(r.atMs), 20.sp, Hud.SECONDARY)
                    Label("  ${r.text}", 22.sp, Hud.PRIMARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (i == m.nav.todayRow) OutlinedBox(highlighted = true) { line() }
            else Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { line() }
        }
    }
}

/** Status (Max only): interval, phone link, AI health, queue depth, last sent — short human text. */
@Composable
fun StatusPage(m: HudModel) {
    val f = m.frame
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("STATUS", 20.sp, Hud.SECONDARY)
        StatusLine("Capture", "every ${formatInterval(f?.intervalSec ?: m.settings.captureIntervalSec)}")
        StatusLine("Phone", HudCopy.phoneLink(m.connection))
        StatusLine("AI", when (val h = f?.aiHealth) { null, Health.Ok -> "ok"; is Health.Degraded -> h.text; is Health.Blocked -> h.text })
        StatusLine("Queue", "${f?.queueDepth ?: 0} waiting")
        StatusLine("Last sent", f?.lastSentAtMs?.let { formatClock(it) } ?: "—")
        f?.error?.let { StatusLine("Note", it) }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, 20.sp, Hud.SECONDARY)
        Label("  $value", 22.sp, Hud.PRIMARY, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
