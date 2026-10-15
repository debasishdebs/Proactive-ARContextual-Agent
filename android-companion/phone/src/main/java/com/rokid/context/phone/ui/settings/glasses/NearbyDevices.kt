package com.rokid.context.phone.ui.settings.glasses

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.carriesData
import com.rokid.context.core.ui.components.GlassesIcon
import com.rokid.context.phone.hub.KeepRunningNearby
import com.rokid.context.phone.ui.list.ActionResult
import com.rokid.context.phone.ui.list.ItemAction
import com.rokid.context.phone.ui.list.ItemStatus
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.list.ListScreen
import com.rokid.context.phone.ui.list.ListSource
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import kotlinx.coroutines.launch

/**
 * Settings > Rokid Glasses > Nearby devices (port of LiveFit `NearbyDevicesSource` onto the generic [ListScreen]):
 * the paired Rokid glasses with "Keep running when nearby". [paired]: Hi Rokid authorized this app (the link holds
 * its pairing). Tapping a paired row flips the switch; an unpaired row opens Rokid Glasses settings (Re-authorize).
 */
class NearbyDevicesSource(
    private val paired: () -> Boolean,
    private val link: () -> LinkState,
    private val keepRunning: KeepRunningNearby,
    private val openGlassesSettings: () -> Unit,
    /** The wearer exited the glasses app on purpose (`pc_user_exit`): the phone stands down until they reopen it. */
    private val closedByUser: () -> Boolean = { false },
) : ListSource {
    override val title = "Nearby devices"
    override val searchHint = "Search devices"
    override val sortable = false

    override suspend fun load(): List<ListItem> {
        val isPaired = paired()
        val on = keepRunning.enabled()
        return listOf(
            ListItem(
                id = GLASSES_ID, title = "Rokid glasses", subtitle = if (isPaired && closedByUser()) CLOSED_BY_USER else subtitle(isPaired, link(), on), icon = GlassesIcon,
                status = if (isPaired) ItemStatus.None else ItemStatus.ActionNeeded, toggle = on.takeIf { isPaired },
            ),
        )
    }

    override fun actionFor(item: ListItem): ItemAction {
        val on = item.toggle ?: return ItemAction { openGlassesSettings(); ActionResult.Silent }
        return ItemAction { keepRunning.set(!on); ActionResult.Silent }
    }

    companion object {
        const val GLASSES_ID = "rokid_glasses"
        const val CLOSED_BY_USER = "Glasses app closed by you; reopen from Settings \u2192 Glasses"
        const val NOTE = "Keep running when nearby, and reopen the glasses app after Rokid closes it (for a call, message or music). When off, Proactive Context stops in the background once you leave the app, even while the glasses are connected, and does not reopen it."

        fun subtitle(paired: Boolean, link: LinkState, keepRunning: Boolean): String {
            if (!paired) return "Not paired \u00b7 authorize in Rokid Glasses settings"
            val state = if (link.carriesData) "Connected" else "Paired"
            return "$state \u00b7 " + if (keepRunning) "keeps running \u00b7 reopens after Rokid closes it" else "stops when the app is closed"
        }
    }
}

@Composable
fun NearbyDevicesRoute(source: NearbyDevicesSource, onBack: () -> Unit) {
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        ListScreen(source, onBack = onBack, onMessage = { scope.launch { snack.showSnackbar(it) } }, header = {
            SettingsCard { NoticeText(NearbyDevicesSource.NOTE, tag = "nearby_note") }
        })
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}
