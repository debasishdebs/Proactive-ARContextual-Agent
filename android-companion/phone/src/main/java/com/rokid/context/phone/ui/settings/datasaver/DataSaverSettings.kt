package com.rokid.context.phone.ui.settings.datasaver

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.ai.datasaver.DataSaverGraph
import com.rokid.context.core.net.DataMode
import com.rokid.context.core.net.DataSaverSettings
import com.rokid.context.core.net.NetworkCost
import com.rokid.context.core.net.SkippedLedger
import com.rokid.context.phone.ui.settings.ChoiceRow
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the Data saver screen shows. */
data class DataSaverUiState(
    val settings: DataSaverSettings = DataSaverSettings(),
    val cost: NetworkCost = NetworkCost.UNKNOWN,
    val today: SkippedLedger.Today = SkippedLedger.Today(0, 0, 0),
)

/** All the screen's words and rules, pure so a JVM test pins them. Nothing here names a country, a network or a place. */
object DataSaverModel {
    const val TITLE = "Data saver"
    const val ROW_TITLE = "Data saver"

    /** Option labels, in [DataMode] order. */
    val MODE_LABELS = listOf("Wi-Fi only", "Wi-Fi and home mobile data", "Anywhere")
    const val MODE_TITLE = "Photos and big uploads"

    const val ABROAD_TITLE = "I'm abroad"
    const val ABROAD_NOTE = "Treat all mobile data as roaming, whatever the phone reports. Turn it on before you travel."
    const val SMALL_TITLE = "Small requests on roaming"
    const val SMALL_NOTE = "Questions, place facts and suggestions as text. Never photos."
    const val TODAY_TITLE = "Skipped today"
    const val COUNTS_ONLY = "Counts only. Nothing waits in a hidden list: photos stay on the phone and upload on Wi-Fi."
    const val NO_LIMIT_YET = "There is no daily limit yet."

    fun modeNote(mode: DataMode): String = when (mode) {
        DataMode.WIFI_ONLY -> "Photos and other big uploads wait for Wi-Fi."
        DataMode.WIFI_AND_HOME_MOBILE -> "Also uses mobile data at home. Never when roaming or unknown."
        DataMode.ANYWHERE -> "Any connection, roaming too. $NO_LIMIT_YET While \"I'm abroad\" is on this acts like \"Wi-Fi and home mobile data\"."
    }

    fun nowLine(cost: NetworkCost, abroad: Boolean): String = when (cost) {
        NetworkCost.FREE -> "Now: Wi-Fi"
        NetworkCost.HOME_MOBILE -> "Now: Home mobile data"
        NetworkCost.ROAMING -> if (abroad) "Now: Mobile data, treated as roaming (I'm abroad)" else "Now: Roaming (the phone reports roaming)"
        NetworkCost.UNKNOWN -> "Now: Unknown network, treated as roaming"
        NetworkCost.OFFLINE -> "Now: Offline"
    }

    /** One line under "Now": what that means for photos. */
    fun nowExplain(cost: NetworkCost, s: DataSaverSettings): String = when (cost) {
        NetworkCost.FREE -> "Photos upload normally."
        NetworkCost.OFFLINE -> "Nothing can leave the phone."
        else -> if (com.rokid.context.core.net.DataPolicy.decide(com.rokid.context.core.net.DataKind.PHOTO, com.rokid.context.core.net.EndpointReach.REMOTE, cost, com.rokid.context.core.net.Transport.CELLULAR, s).allowed)
            "Photos upload on this connection." else "Photos wait for Wi-Fi."
    }

    /** The row subtitle in Settings. */
    fun rowSubtitle(s: DataSaverSettings): String = when {
        s.abroad -> "Abroad: photos on Wi-Fi only" // abroad, every mode keeps photos off mobile data (see DataSaverSettings.effectiveMode)
        s.mode == DataMode.WIFI_ONLY -> "Photos on Wi-Fi only"
        s.mode == DataMode.WIFI_AND_HOME_MOBILE -> "Photos on Wi-Fi and home mobile data"
        else -> "Photos on any connection"
    }

    fun todayLines(t: SkippedLedger.Today): List<String> = listOf(
        "Photo uploads waiting for Wi-Fi: ${t.uploadsQueued}",
        "Optional photo calls skipped (place cards, intent, Ask photos): ${t.dropped}",
        "Other big jobs left for Wi-Fi (night describe, text calls): ${t.bulk}",
    )
}

/** What the screen needs (production: [AndroidDataSaverControl]; tests: a fake). */
interface DataSaverControl {
    val state: StateFlow<DataSaverUiState>
    fun setMode(m: DataMode)
    fun setAbroad(on: Boolean)
    fun setSmallOnRoaming(on: Boolean)
    /** Re-reads the network cost and today's counts (they change outside the screen). */
    fun refresh()
}

/** Production control over the app's one [DataSaverGraph]. A mode change also re-ensures the night-job constraints (they follow the mode) while processing is on. */
class AndroidDataSaverControl(private val app: Context, private val graph: DataSaverGraph = DataSaverGraph.get(app)) : DataSaverControl {
    private val flow = MutableStateFlow(read())
    override val state: StateFlow<DataSaverUiState> = flow.asStateFlow()

    private fun read() = DataSaverUiState(graph.settingsNow(), graph.costNow(), graph.ledger.today())

    override fun setMode(m: DataMode) {
        graph.settingsStore.setMode(m); refresh()
        try { if (com.rokid.context.data.runtime.AppConfigStore.isPipelineEnabled(app)) com.rokid.context.work.OrchestrationScheduler(app).ensureNightIndex() } catch (_: Exception) { /* the runtime gate still holds */ }
    }
    override fun setAbroad(on: Boolean) { graph.settingsStore.setAbroad(on); refresh() }
    override fun setSmallOnRoaming(on: Boolean) { graph.settingsStore.setSmallOnRoaming(on); refresh() }
    override fun refresh() { flow.value = read() }
}

@Composable
fun DataSaverRoute(control: DataSaverControl, back: () -> Unit) {
    val state by control.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { control.refresh(); delay(2_000) } } // the network and the counts change outside this screen
    DataSaverScreen(state, back, control::setMode, control::setAbroad, control::setSmallOnRoaming)
}

@Composable
fun DataSaverScreen(state: DataSaverUiState, back: () -> Unit, onMode: (DataMode) -> Unit, onAbroad: (Boolean) -> Unit, onSmall: (Boolean) -> Unit) {
    val s = state.settings
    SettingsPage(DataSaverModel.TITLE, back, tag = "data_saver_settings") {
        item {
            SettingsCard {
                NoticeText(DataSaverModel.nowLine(state.cost, s.abroad), tag = "data_saver_now")
                NoticeText(DataSaverModel.nowExplain(state.cost, s), tag = "data_saver_now_explain")
            }
        }
        item {
            SettingsCard {
                ChoiceRow(DataSaverModel.MODE_TITLE, DataSaverModel.MODE_LABELS, s.mode.ordinal, { onMode(DataMode.entries[it]) }, tag = "data_saver_mode")
                NoticeText(DataSaverModel.modeNote(s.mode), tag = "data_saver_mode_note")
                SwitchRow(DataSaverModel.ABROAD_TITLE, DataSaverModel.ABROAD_NOTE, s.abroad, onAbroad, tag = "data_saver_abroad")
                SwitchRow(DataSaverModel.SMALL_TITLE, DataSaverModel.SMALL_NOTE, s.smallOnRoaming, onSmall, tag = "data_saver_small_roaming")
            }
        }
        item {
            SettingsCard {
                NoticeText(DataSaverModel.TODAY_TITLE, tag = "data_saver_today_title")
                DataSaverModel.todayLines(state.today).forEachIndexed { i, line -> NoticeText(line, tag = "data_saver_status_$i") }
                NoticeText(DataSaverModel.COUNTS_ONLY, tag = "data_saver_status")
            }
        }
    }
}
