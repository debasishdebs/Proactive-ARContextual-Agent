package com.rokid.context.connectors.actions

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The three v1 action types (spec §4.4); stored as `proposal.type` by name. */
enum class ActionType { REMINDER, SHOPPING_ADD, CALENDAR_EVENT }

data class ActionToggle(val enabled: Boolean, val alwaysConfirm: Boolean = true)

/**
 * Settings › Actions & tools. Defaults: Reminder on, Shopping on, Calendar off (needs WRITE_CALENDAR); always-confirm on.
 * The prefs are opened and read on first use, never at construction (the graph may be built on Main).
 */
class ActionSettingsStore(prefsProvider: () -> SharedPreferences) {
    constructor(prefs: SharedPreferences) : this({ prefs })

    private val lock = Any()
    private val prefs by lazy(prefsProvider)
    private val state by lazy { MutableStateFlow(read()) }
    val settings: StateFlow<Map<ActionType, ActionToggle>> get() = state.asStateFlow()

    fun set(t: ActionType, v: ActionToggle) = synchronized(lock) {
        prefs.edit().putBoolean(enabledKey(t), v.enabled).putBoolean(confirmKey(t), v.alwaysConfirm).apply()
        state.value = state.value + (t to v)
    }

    fun toggle(t: ActionType): ActionToggle = state.value[t] ?: default(t)

    private fun read(): Map<ActionType, ActionToggle> = ActionType.entries.associateWith {
        val d = default(it)
        ActionToggle(prefs.getBoolean(enabledKey(it), d.enabled), prefs.getBoolean(confirmKey(it), d.alwaysConfirm))
    }

    companion object {
        const val PREFS = "action_settings"
        fun default(t: ActionType) = ActionToggle(enabled = t != ActionType.CALENDAR_EVENT, alwaysConfirm = true)
        private fun enabledKey(t: ActionType) = "enabled_${t.name.lowercase()}"
        private fun confirmKey(t: ActionType) = "confirm_${t.name.lowercase()}"
    }
}
