package com.rokid.context.phone.ui.settings.datasaver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.core.net.DailyCaps
import com.rokid.context.core.net.DailyLimit
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.MobileDataLedger
import com.rokid.context.core.net.NetworkCosts
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.SkippedLedger
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Data saver screen shows. */
data class DataSaverUiState(
    val settings: DataSaverSettings = DataSaverSettings(),
    val cost: NetworkCost = NetworkCost.UNKNOWN,
    val today: SkippedLedger.Today = SkippedLedger.Today(0, 0, 0),
    /** Slice 2: what went out over costly networks today (numbers only). */
    val mobile: MobileDataLedger.Today = MobileDataLedger.Today(0, 0, 0, 0),
    /** The daily limit that applies on the network right now (null = none). */
    val limit: DailyLimit? = null,
    /** Today's limit is used up on this network: photos and big uploads wait until midnight. */
    val limitReached: Boolean = false,
    /** Frames the data saver is holding for Wi-Fi right now (the live count of frames parked by it; it does not grow with every recheck). */
    val waiting: Int = 0,
    /** Wi-Fi connected and unmetered but not validated for over 10 minutes. */
    val stuck: Boolean = false,
    /** Why the network is ROAMING: [NetworkCosts.WHY_ABROAD], [NetworkCosts.WHY_REPORTED], [NetworkCosts.WHY_COUNTRY] or "". */
    val why: String = "",
)

/** All the screen's words and rules, pure so a JVM test pins them. Nothing here names a country, a network or a place. */
object DataSaverModel {
    const val TITLE = "Data saver"
    const val ROW_TITLE = "Data saver"

    /** Option labels, in [DataMode] order. */
    val MODE_LABELS = listOf("Wi-Fi only", "Wi-Fi and home mobile data (default)", "Anywhere")
    const val MODE_TITLE = "Photos and big uploads"

    const val ABROAD_TITLE = "I'm abroad"
    const val ABROAD_NOTE = "Treat all mobile data as roaming, whatever the phone reports. Turn it on before you travel."
    const val SMALL_TITLE = "Small requests on roaming"
    const val SMALL_NOTE = "Questions, place facts and suggestions as text. Never photos."
    const val TODAY_TITLE = "Blocked today"
    const val COUNTS_ONLY = "Counts only. Photos stay on the phone and upload on Wi-Fi; the same photo can be counted by more than one call."
    const val COUNTED_NOTE = "Sent today counts model calls, Place Guide fetches and street-map tiles loaded on mobile data. Where photos would wait (roaming, unknown network, or your daily limit used up), the street map shows only tiles already saved on the phone."
    const val CAP_NOTE = "Up to the newest 300 waiting photos are kept for Wi-Fi. In a longer backlog the older ones are thinned like any backlog (the salient ones are still described)."
    const val LIMIT_ABROAD_NOTE = "While \"I'm abroad\" is on or the phone reports roaming, photos stop for the day at 10 MB or 30 photos (your own limit if lower) and wait for Wi-Fi. At home on Wi-Fi or home mobile data there is no limit unless you set one below."

    const val LIMIT_TITLE = "Daily limit on mobile data"
    const val LIMIT_NOTE = "Off by default. When on, photos and big uploads stop for the day at your numbers and wait for Wi-Fi. Text questions still go. Resets at midnight."
    val MB_LABELS = DailyCaps.MB_CHOICES.map { "$it MB" }
    const val MB_TITLE = "Data per day on mobile data"
    val PHOTO_LABELS = DailyCaps.PHOTO_CHOICES.map { "$it" }
    const val PHOTO_TITLE = "Photo uploads per day on mobile data"
    const val WIFI_METERED_TITLE = "Treat Wi-Fi as metered while I'm abroad"
    const val WIFI_METERED_NOTE = "For a phone hotspot or pocket Wi-Fi that looks like free Wi-Fi. Only while \"I'm abroad\" is on: then no Wi-Fi counts as free, so photos wait until you turn this off on hotel Wi-Fi."
    const val LIMIT_REACHED_LINE = "Daily limit reached. Photos and big uploads wait until midnight or Wi-Fi; text questions still go."

    private const val MB = 1024.0 * 1024.0
    /** "412 KB" under a megabyte, else "1.2 MB". */
    fun size(bytes: Long): String = if (bytes < 1024L * 1024L) "${(bytes + 512) / 1024} KB" else "${java.lang.String.format(java.util.Locale.ROOT, "%.1f", bytes / MB)} MB"

    fun sentLine(m: MobileDataLedger.Today): String = "Sent today on mobile data: ${size(m.bytes)} / ${m.photos} photos"
    /** "Limit today: 10 MB and 30 photos" (only when one applies). */
    fun limitLine(l: DailyLimit?): String? = l?.let { "Limit today on this connection: ${size(it.bytes)} and ${it.photos} photos" }

    fun modeNote(mode: DataMode): String = when (mode) {
        DataMode.WIFI_ONLY -> "Photos and other big uploads wait for Wi-Fi."
        DataMode.WIFI_AND_HOME_MOBILE -> "Also uses mobile data at home. Never when roaming or unknown."
        DataMode.ANYWHERE -> "Any connection, roaming too. $LIMIT_ABROAD_NOTE"
    }

    const val STUCK_LINE = "Now: Wi-Fi without an internet check for over 10 minutes. Photos to remote models wait; your own box on this network still works."

    const val ANDROID_SAVER_LINE = "Now: Android Data Saver is on for this app. Photos wait for Wi-Fi (Android blocks background mobile data)."

    fun nowLine(cost: NetworkCost, abroad: Boolean, stuck: Boolean = false, why: String = ""): String = if (stuck && cost == NetworkCost.UNKNOWN) STUCK_LINE else when (cost) {
        NetworkCost.RESTRICTED -> ANDROID_SAVER_LINE
        NetworkCost.FREE -> "Now: Wi-Fi"
        NetworkCost.HOME_MOBILE -> "Now: Home mobile data"
        NetworkCost.ROAMING -> when {
            why == com.rokid.context.core.net.NetworkCosts.WHY_COUNTRY -> "Now: Mobile data, treated as roaming (the SIM country differs from the network country)"
            why == com.rokid.context.core.net.NetworkCosts.WHY_REPORTED -> "Now: Roaming (the phone reports roaming)"
            abroad -> "Now: Mobile data, treated as roaming (I'm abroad)"
            else -> "Now: Roaming (the phone reports roaming)"
        }
        NetworkCost.UNKNOWN -> "Now: Unknown network, treated as roaming"
        NetworkCost.OFFLINE -> "Now: Offline"
    }

    /** One line under "Now": what that means for photos. */
    fun nowExplain(cost: NetworkCost, s: DataSaverSettings, limitReached: Boolean = false): String = if (limitReached && NetworkCosts.isCostly(cost)) LIMIT_REACHED_LINE else when (cost) {
        NetworkCost.FREE -> "Photos upload normally."
        NetworkCost.OFFLINE -> "Nothing can leave the phone."
        NetworkCost.RESTRICTED -> "Photos wait for Wi-Fi."
        else -> if (com.rokid.context.core.net.DataPolicy.decide(com.rokid.context.core.net.DataKind.PHOTO, com.rokid.context.core.net.EndpointReach.REMOTE, cost, com.rokid.context.core.net.Transport.CELLULAR, s).allowed)
            "Photos upload on this connection." else "Photos wait for Wi-Fi."
    }

    /** The row subtitle in Settings. */
    fun rowSubtitle(s: DataSaverSettings): String = when {
        s.abroad && s.mode == DataMode.ANYWHERE -> "Abroad: photos up to the daily limit"
        s.abroad -> "Abroad: photos on Wi-Fi only"
        s.mode == DataMode.WIFI_ONLY -> "Photos on Wi-Fi only"
        s.mode == DataMode.WIFI_AND_HOME_MOBILE -> "Photos on Wi-Fi and home mobile data"
        else -> "Photos on any connection"
    }

    fun todayLines(t: SkippedLedger.Today, waiting: Int = 0): List<String> = listOf(
        "Photos waiting for Wi-Fi now: $waiting",
        "Blocked photo-upload calls today: ${t.uploadsQueued} (a call carries up to 3 photos and is tried again later)",
        "Optional photo calls skipped (place matches, intent, Ask photos): ${t.dropped}",
        "Other big jobs left for Wi-Fi (night describe, text calls): ${t.bulk}",
    )
}

/** What the screen needs (production: `phone.datasaver.AndroidDataSaverControl`; tests: a fake). */
interface DataSaverControl {
    val state: StateFlow<DataSaverUiState>
    fun setMode(m: DataMode)
    fun setAbroad(on: Boolean)
    fun setSmallOnRoaming(on: Boolean)
    fun setDailyLimitOn(on: Boolean) {}
    fun setLimitMb(mb: Int) {}
    fun setLimitPhotos(n: Int) {}
    fun setWifiMeteredAbroad(on: Boolean) {}
    /** Re-reads the network cost and today's counts (they change outside the screen). */
    fun refresh()
    /** Keeps [refresh]ing off the main thread for as long as the screen is shown (the caller cancels it); the polling lives with the control, not in the settings file. */
    suspend fun poll() {}
}

@Composable
fun DataSaverRoute(control: DataSaverControl, back: () -> Unit) {
    val state by control.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { control.poll() }
    DataSaverScreen(state, back, control::setMode, control::setAbroad, control::setSmallOnRoaming, control::setDailyLimitOn, control::setLimitMb, control::setLimitPhotos, control::setWifiMeteredAbroad)
}

@Composable
fun DataSaverScreen(
    state: DataSaverUiState, back: () -> Unit, onMode: (DataMode) -> Unit, onAbroad: (Boolean) -> Unit, onSmall: (Boolean) -> Unit,
    onLimitOn: (Boolean) -> Unit = {}, onLimitMb: (Int) -> Unit = {}, onLimitPhotos: (Int) -> Unit = {}, onWifiMetered: (Boolean) -> Unit = {},
) {
    val s = state.settings
    SettingsPage(DataSaverModel.TITLE, back, tag = "data_saver_settings") {
        item {
            SettingsCard {
                NoticeText(DataSaverModel.nowLine(state.cost, s.abroad, state.stuck, state.why), tag = "data_saver_now")
                NoticeText(DataSaverModel.nowExplain(state.cost, s, state.limitReached), tag = "data_saver_now_explain")
            }
        }
        item {
            SettingsCard {
                ChoiceRow(DataSaverModel.MODE_TITLE, DataSaverModel.MODE_LABELS, s.mode.ordinal, { onMode(DataMode.entries[it]) }, tag = "data_saver_mode")
                NoticeText(DataSaverModel.modeNote(s.mode), tag = "data_saver_mode_note")
                SwitchRow(DataSaverModel.ABROAD_TITLE, DataSaverModel.ABROAD_NOTE, s.abroad, onAbroad, tag = "data_saver_abroad")
                SwitchRow(DataSaverModel.SMALL_TITLE, DataSaverModel.SMALL_NOTE, s.smallOnRoaming, onSmall, tag = "data_saver_small_roaming")
                SwitchRow(DataSaverModel.WIFI_METERED_TITLE, DataSaverModel.WIFI_METERED_NOTE, s.wifiMeteredAbroad, onWifiMetered, tag = "data_saver_wifi_metered")
            }
        }
        item {
            SettingsCard {
                SwitchRow(DataSaverModel.LIMIT_TITLE, DataSaverModel.LIMIT_NOTE, s.dailyLimitOn, onLimitOn, tag = "data_saver_limit_on")
                if (s.dailyLimitOn) {
                    ChoiceRow(DataSaverModel.MB_TITLE, DataSaverModel.MB_LABELS, DailyCaps.MB_CHOICES.indexOf(s.limitMb).coerceAtLeast(0), { onLimitMb(DailyCaps.MB_CHOICES[it]) }, tag = "data_saver_cap_bytes")
                    ChoiceRow(DataSaverModel.PHOTO_TITLE, DataSaverModel.PHOTO_LABELS, DailyCaps.PHOTO_CHOICES.indexOf(s.limitPhotos).coerceAtLeast(0), { onLimitPhotos(DailyCaps.PHOTO_CHOICES[it]) }, tag = "data_saver_cap_photos")
                }
            }
        }
        item {
            SettingsCard {
                NoticeText(DataSaverModel.sentLine(state.mobile), tag = "data_saver_sent")
                DataSaverModel.limitLine(state.limit)?.let { NoticeText(it, tag = "data_saver_limit_line") }
                NoticeText(DataSaverModel.TODAY_TITLE, tag = "data_saver_today_title")
                DataSaverModel.todayLines(state.today, state.waiting).forEachIndexed { i, line -> NoticeText(line, tag = "data_saver_status_$i") }
                NoticeText(DataSaverModel.CAP_NOTE, tag = "data_saver_cap_note")
                NoticeText(DataSaverModel.COUNTED_NOTE, tag = "data_saver_counted_note")
                NoticeText(DataSaverModel.COUNTS_ONLY, tag = "data_saver_status")
            }
        }
    }
}
