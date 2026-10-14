package com.rokid.context.phone.ui.settings.connectors

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rokid.context.ai.SafeLog
import com.rokid.context.connectors.actions.ActionSettingsStore
import com.rokid.context.connectors.actions.ActionToggle
import com.rokid.context.connectors.actions.ActionType
import com.rokid.context.core.ui.theme.PcColors
import com.rokid.context.phone.ui.actions.CalendarAccess
import com.rokid.context.phone.ui.actions.rememberCalendarAccess
import com.rokid.context.phone.ui.settings.NoticeText
import com.rokid.context.phone.ui.settings.SecondaryAction
import com.rokid.context.phone.ui.settings.SettingsCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rokid.context.phone.ui.settings.SettingsDeps
import com.rokid.context.phone.ui.settings.SettingsPage
import com.rokid.context.phone.ui.settings.SwitchRow

/**
 * Settings › Actions & tools (R-62, R-63). The store comes from [SettingsDeps.actions] (the lazy actions graph), built and
 * first read on IO, so "starting" clears by itself and Main never reads its prefs.
 */
@Composable
fun ActionsRoute(deps: SettingsDeps, back: () -> Unit, openShopping: () -> Unit, openNotes: () -> Unit = {}) {
    val loaded by produceState<ActionSettingsStore?>(null, deps) {
        value = try {
            withContext(Dispatchers.IO) { deps.actions.settings.also { it.settings.value } }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w("ActionsRoute", "actions unavailable: ${SafeLog.describe(e)}")
            null
        }
    }
    val access = rememberCalendarAccess()
    val store = loaded
    if (store == null) {
        SettingsPage("Actions & tools", back, tag = "settings_actions") { item { SettingsCard { NoticeText("Actions are starting \u2014 come back in a moment") } } }
        return
    }
    ActionsSettings(store, requestCalendar = access.request, back = back, openShopping = openShopping, openAppSettings = access.openAppSettings, openNotes = openNotes)
}

/** Stateless over [store]; [requestCalendar] asks for calendar access and reports the result. */
@Composable
fun ActionsSettings(
    store: ActionSettingsStore, requestCalendar: ((CalendarAccess) -> Unit) -> Unit, back: () -> Unit, openShopping: () -> Unit,
    openAppSettings: () -> Unit = {}, openNotes: () -> Unit = {},
) {
    val toggles by store.settings.collectAsStateWithLifecycle()
    var calendarDenied by remember { mutableStateOf<CalendarAccess?>(null) }
    SettingsPage("Actions & tools", back, tag = "settings_actions") {
        ActionType.entries.forEach { type ->
            val t = toggles[type] ?: ActionSettingsStore.default(type)
            item {
                SettingsCard {
                    SwitchRow(title(type), subtitle(type), t.enabled, onChange = { on ->
                        if (on && type == ActionType.CALENDAR_EVENT) {
                            requestCalendar { result ->
                                calendarDenied = result.takeIf { it != CalendarAccess.GRANTED }
                                if (result == CalendarAccess.GRANTED) store.set(type, t.copy(enabled = true))
                            }
                        } else {
                            store.set(type, t.copy(enabled = on))
                        }
                    }, tag = "enabled_${type.name}")
                    SwitchRow("Always confirm", "Ask on the glasses or phone first", t.alwaysConfirm, onChange = { store.set(type, ActionToggle(t.enabled, it)) },
                        tag = "confirm_${type.name}", enabled = t.enabled)
                    if (type == ActionType.CALENDAR_EVENT && calendarDenied != null && !t.enabled) {
                        NoticeText("Calendar permission needed", error = true, tag = "calendar_denied")
                        if (calendarDenied == CalendarAccess.DENIED_FOREVER) SecondaryAction("Open app settings", openAppSettings, tag = "open_app_settings")
                    }
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
                LinkRow("Notes", "Things you asked me to note", Modifier.clickable(onClick = openNotes).testTag("open_notes"))
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
    ActionType.NOTE_ADD -> "Notes"
}

private fun subtitle(t: ActionType) = when (t) {
    ActionType.REMINDER -> "Set a reminder when you ask for one"
    ActionType.SHOPPING_ADD -> "Add items you mention"
    ActionType.CALENDAR_EVENT -> "Add events to your calendar"
    ActionType.NOTE_ADD -> "Save things you ask me to note"
}
