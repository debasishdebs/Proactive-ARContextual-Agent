package com.rokid.context.phone.hub

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings > Rokid Glasses > Nearby devices: "Keep running when nearby" (user decision 2026-10-15). ON (default) is
 * today's behaviour: the hub service keeps running in the background while the glasses are linked. OFF: the service
 * stops once the phone app leaves the front, even while connected. Kept in the restorable `hub_settings` file.
 */
class KeepRunningNearby(private val prefs: SharedPreferences) {
    fun enabled(): Boolean = prefs.getBoolean(KEY, true)
    fun set(on: Boolean) { prefs.edit().putBoolean(KEY, on).apply() }

    companion object {
        const val KEY = "keep_running_nearby"
        fun of(context: Context) = KeepRunningNearby(context.applicationContext.getSharedPreferences(HubSettingsStore.PREFS, Context.MODE_PRIVATE))
    }
}
