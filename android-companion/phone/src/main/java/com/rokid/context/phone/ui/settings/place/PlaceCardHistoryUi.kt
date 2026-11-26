package com.rokid.context.phone.ui.settings.place

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the history list needs from the card history and the glasses path (production: [com.rokid.context.phone.place.AndroidPlaceGuideControl]; tests: fakes). */
interface PlaceHistoryControl {
    /** Emits whenever the stored history changed (and once on subscribe): the screen re-reads then, no polling. */
    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.emptyFlow()
    /** The Developer flag of the history (default OFF in production): off = the list shows one line and "Show on glasses again" is disabled. */
    val historyOn: Boolean get() = true
    /** Newest first, at most 500. */
    suspend fun rows(): List<PlaceCardLogEntity>
    /** The card line, re-read from the pack or the pin by place id; null = the place is no longer in the pack. */
    suspend fun line(row: PlaceCardLogEntity): String?
    /** The photo file of a LIVE frame (for the thumbnail); null = no photo (gone, or never linked). */
    suspend fun photoPath(frameId: String): String?
    suspend fun delete(id: String): Boolean
    /** Why "Show on glasses again" is off for [row] right now (null = allowed). Reads the switch, the zone state and the link. */
    fun showAgainBlock(row: PlaceCardLogEntity): String?
    /** Publishes a copy of the card (a user act: a new card id and a new history row of the same kind). False = nothing was shown. */
    suspend fun showAgain(row: PlaceCardLogEntity): Boolean
}

/** One history row as the list shows it. */
data class PlaceHistoryRowUi(
    val id: String, val title: String, val atMs: Long, val kind: String, val delivery: String, val placeId: String,
    val members: List<String>, val frameId: String?, val photoGone: Boolean, val confPct: Int?,
) {
    val kindText: String get() = PlaceHistoryModel.kindLabel(kind)
    val deliveryText: String get() = PlaceHistoryModel.deliveryLabel(delivery)
}

fun PlaceCardLogEntity.toUi() = PlaceHistoryRowUi(id, title, at, kind, delivery, placeId, members?.split(',')?.filter { it.isNotEmpty() }.orEmpty(), frameId, photoGone == 1, confPct)

/** The detail of one row: the line (null = place gone), the live photo, and the reason "Show on glasses again" is off. */
data class PlaceHistoryDetail(val row: PlaceHistoryRowUi, val line: String?, val photoPath: String?, val showAgainBlock: String?, val message: String? = null, val busy: Boolean = false)

/** What "Show on glasses again" depends on, read by the control at the time of the tap. */
data class ShowAgainEnv(val placeGuideOn: Boolean, val zoneBlocked: Boolean, val glassesConnected: Boolean)

object PlaceHistoryModel {
    const val OFF_LINE = "Card history is off"
    const val EMPTY = "No place cards yet"
    const val SECTION = "Recent place cards"
    const val NOTE = "Every place card is kept here on this phone, also when the glasses screen was off. Forgetting its photo, a range Forget, Delete all and deleting its pin remove the rows. Not in any backup or export."
    const val GONE_LINE = "This place is no longer in your pack"
    const val PHOTO_GONE = "Photo gone"
    const val PAGE = 20

    fun kindLabel(kind: String): String = when (kind) {
        "PHOTO" -> "photo match"
        "PIN" -> "your pin"
        "PIN_CLUSTER" -> "several pins"
        "NEARBY" -> "nearby · not checked"
        "MAYBE" -> "maybe · offline guess"
        "TEXT" -> "sign read"
        "POSSIBLE" -> "possible · not shown"
        else -> "place card"
    }

    fun deliveryLabel(state: String): String = when (state) {
        CardDelivery.PENDING -> "waiting to be sent"
        CardDelivery.SENT -> "sent to glasses"
        CardDelivery.NOT_SENT_LINK -> "not sent: glasses not connected"
        CardDelivery.NOT_SENT_HOST -> "not sent: Nexus shows the HUD"
        CardDelivery.NOT_SENT_ZONE -> "not sent: pause zone"
        CardDelivery.NOT_SENT_POSSIBLE -> "possible · not shown"
        CardDelivery.NOT_SENT_LATE -> "too late for the glasses"
        CardDelivery.NOT_SENT_BUSY -> "not sent: another message was showing"
        else -> "unknown"
    }

    /** "d MMM HH:mm" in the phone's zone. */
    fun timeLabel(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.getDefault()).format(Instant.ofEpochMilli(atMs).atZone(zone))

    /** The row's line under its title: kind, then delivery (once, when they say the same). */
    fun summary(r: PlaceHistoryRowUi): String = if (r.kindText == r.deliveryText) r.kindText else "${r.kindText} · ${r.deliveryText}"

    /**
     * Why "Show on glasses again" is off (null = on): Place Guide is off, a zone is approached or active, the glasses are not connected, a photo match whose photo is gone, or a possible match (never shown as a card).
     * The place being gone from the pack is checked by the caller (the line is read).
     */
    fun showAgainBlock(kind: String, frameId: String?, photoGone: Boolean, env: ShowAgainEnv): String? = when {
        !env.placeGuideOn -> "Place Guide is off"
        env.zoneBlocked -> "A pause zone is active or near"
        !env.glassesConnected -> "The glasses are not connected"
        kind == "POSSIBLE" -> "This was only a possible match"
        kind == "PHOTO" && (frameId == null || photoGone) -> "The photo is gone"
        else -> null
    }
}

/** The "Recent place cards" section of the Place Guide screen. */
fun LazyListScope.placeHistoryItems(rows: List<PlaceHistoryRowUi>, shown: Int, zone: ZoneId, onOpen: (String) -> Unit, onMore: () -> Unit, historyOn: Boolean = true) {
    if (!historyOn) {
        item(key = "place_history_head") { SettingsCard { NoticeText(PlaceHistoryModel.OFF_LINE, tag = "place_history_off") } }
        return
    }
    item(key = "place_history_head") {
        SettingsCard {
            Text(PlaceHistoryModel.SECTION, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("place_history_title"))
            NoticeText(PlaceHistoryModel.NOTE, tag = "place_history_note")
            if (rows.isEmpty()) NoticeText(PlaceHistoryModel.EMPTY, tag = "place_history_empty")
        }
    }
    rows.take(shown).forEach { r ->
        item(key = "place_history_${r.id}") {
            SettingsCard {
                Column(Modifier.fillMaxWidth().clickable { onOpen(r.id) }.testTag("place_history_row_${r.id}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall)
                    NoticeText("${PlaceHistoryModel.timeLabel(r.atMs, zone)} · ${PlaceHistoryModel.summary(r)}", tag = "place_history_meta_${r.id}")
                }
            }
        }
    }
    if (rows.size > shown) item(key = "place_history_more") { SettingsCard { SecondaryAction("Show more", onMore, tag = "place_history_more") } }
}

/** The detail of one card: its line, the live photo, and the actions. */
@Composable
fun PlaceHistoryDetailDialog(d: PlaceHistoryDetail, zone: ZoneId, onClose: () -> Unit, onShowAgain: () -> Unit, onDelete: () -> Unit, onOpenMoment: ((String) -> Unit)?) {
    val r = d.row
    AlertDialog(
        onDismissRequest = onClose, modifier = Modifier.testTag("place_history_detail"),
        title = { Text(r.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoticeText("${PlaceHistoryModel.timeLabel(r.atMs, zone)} · ${PlaceHistoryModel.summary(r)}", tag = "place_history_detail_meta")
                NoticeText(d.line ?: PlaceHistoryModel.GONE_LINE, tag = "place_history_detail_line")
                r.confPct?.let { NoticeText("Confidence about $it %", tag = "place_history_detail_conf") }
                if (d.photoPath != null) com.rokid.context.core.ui.components.FrameThumb(d.photoPath, Modifier.testTag("place_history_detail_photo"), size = 96.dp)
                else if (r.photoGone) NoticeText(PlaceHistoryModel.PHOTO_GONE, tag = "place_history_detail_photo_gone")
                d.showAgainBlock?.let { NoticeText(it, error = true, tag = "place_history_detail_block") }
                d.message?.let { NoticeText(it, tag = "place_history_detail_message") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onOpenMoment != null && d.photoPath != null && r.frameId != null) SecondaryAction("Open Moment", { onOpenMoment(r.frameId) }, tag = "place_history_open_moment")
                }
                SecondaryAction("Show on glasses again", onShowAgain, tag = "place_history_show_again", enabled = d.showAgainBlock == null && d.line != null && !d.busy)
                SecondaryAction("Delete from history", onDelete, tag = "place_history_delete")
            }
        },
        confirmButton = { TextButton(onClose, Modifier.testTag("place_history_close")) { Text("Close") } },
    )
}
