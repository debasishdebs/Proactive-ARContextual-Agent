package com.rokid.context.phone.ui.settings.connectors

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.connectors.actions.ActionSettingsStore
import com.rokid.context.connectors.actions.ActionToggle
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.connectors.actions.ActionsGraph
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SettingsCard
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow

/**
 * Settings › Actions & tools (R-62, R-63). The engine's store comes from the process graph the hub built
 * ([ActionsGraph.current]): SettingsDeps has no actions member yet (see the Stream B report).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ActionsRoute(deps: SettingsDeps, back: () -> Unit, openShopping: () -> Unit) {
    var pendingCallback by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        pendingCallback?.invoke(r.isNotEmpty() && r.values.all { it }); pendingCallback = null
    }
    val store = remember { ActionsGraph.current()?.settings }
    if (store == null) {
        SettingsPage("Actions & tools", back, tag = "settings_actions") { item { SettingsCard { NoticeText("Actions are starting \u2014 come back in a moment") } } }
        return
    }
    ActionsSettings(store, requestCalendar = { cb ->
        pendingCallback = cb
        launcher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
    }, back = back, openShopping = openShopping)
}

/** Stateless over [store]; [requestCalendar] asks for calendar access and reports the result. */
@Composable
fun ActionsSettings(store: ActionSettingsStore, requestCalendar: ((Boolean) -> Unit) -> Unit, back: () -> Unit, openShopping: () -> Unit) {
    val toggles by store.settings.collectAsStateWithLifecycle()
    var calendarDenied by remember { mutableStateOf(false) }
    SettingsPage("Actions & tools", back, tag = "settings_actions") {
        ActionType.entries.forEach { type ->
            val t = toggles[type] ?: ActionSettingsStore.default(type)
            item {
                SettingsCard {
                    SwitchRow(title(type), subtitle(type), t.enabled, onChange = { on ->
                        if (on && type == ActionType.CALENDAR_EVENT) {
                            requestCalendar { granted ->
                                calendarDenied = !granted
                                if (granted) store.set(type, t.copy(enabled = true))
                            }
                        } else {
                            store.set(type, t.copy(enabled = on))
                        }
                    }, tag = "enabled_${type.name}")
                    SwitchRow("Always confirm", "Ask on the glasses or phone first", t.alwaysConfirm, onChange = { store.set(type, ActionToggle(t.enabled, it)) },
                        tag = "confirm_${type.name}", enabled = t.enabled)
                    if (type == ActionType.CALENDAR_EVENT && calendarDenied && !t.enabled) NoticeText("Calendar permission needed", error = true, tag = "calendar_denied")
                }
            }
        }
        item {
            SettingsCard {
                LinkRow("Shopping list", "Items added by actions", Modifier.clickable(onClick = openShopping).testTag("open_shopping"))
            }
        }
        item {
            SettingsCard {
                LinkRow("MCP servers", "Coming in v2", Modifier.clickable(enabled = false) {}.testTag("mcp_servers"))
            }
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = PcColors.InkSoft)
    }
}

private fun title(t: ActionType) = when (t) {
    ActionType.REMINDER -> "Reminders"
    ActionType.SHOPPING_ADD -> "Shopping list"
    ActionType.CALENDAR_EVENT -> "Calendar events"
}

private fun subtitle(t: ActionType) = when (t) {
    ActionType.REMINDER -> "Set a reminder when you ask for one"
    ActionType.SHOPPING_ADD -> "Add items you mention"
    ActionType.CALENDAR_EVENT -> "Add events to your calendar"
}
