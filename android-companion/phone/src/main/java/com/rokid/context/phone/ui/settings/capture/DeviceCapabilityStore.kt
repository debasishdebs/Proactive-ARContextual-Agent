package com.rokid.context.phone.ui.settings.capture

import android.content.Context
import android.content.SharedPreferences
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.DeviceCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The device capabilities the user edits (v0.9.8 slice D): which mics and displays flows may use ([DeviceCapabilities]). Stores only the SWITCHED-OFF keys; every load drops a key that is not in the
 * catalogue. Prefs file [PREFS] (a per-device user setting like the signal configuration: a restored phone may have other or no glasses; no memory content, so Delete all leaves it; excluded from backup/transfer).
 * Logging by event name only. With no prefs it lives in memory.
 */
class DeviceCapabilityStore(private val prefs: SharedPreferences? = null) {
    private val state = MutableStateFlow(load())
    val capabilities: StateFlow<DeviceCapabilities> = state.asStateFlow()

    private fun load(): DeviceCapabilities = runCatching {
        DeviceCapabilities.clean((Json.parseToJsonElement(prefs?.getString(KEY, null) ?: "[]") as JsonArray).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content })
    }.getOrDefault(DeviceCapabilities.ALL)

    /** False for a device or capability that is not in the catalogue (nothing is stored). */
    @Synchronized fun set(device: String, capability: String, on: Boolean): Boolean {
        if (!DeviceCapabilities.known(device, capability)) { PcLog.w(TAG, "device capability rejected"); return false }
        val next = state.value.with(device, capability, on)
        prefs?.edit()?.putString(KEY, JsonArray(next.disabled.sorted().map { JsonPrimitive(it) }).toString())?.apply()
        state.value = next
        PcLog.i(TAG, "device capability saved")
        return true
    }

    companion object {
        const val PREFS = "device_capabilities"
        private const val KEY = "disabled"
        private const val TAG = "DeviceCapability"

        @Volatile private var instance: DeviceCapabilityStore? = null
        fun get(context: Context): DeviceCapabilityStore = instance ?: synchronized(this) {
            instance ?: DeviceCapabilityStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
