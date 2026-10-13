package com.rokid.context.voice.android

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Phase 0: phone TTS reaches the glasses speaker over BT A2DP; without an A2DP output a glasses answer is HUD-only. */
object AudioRoutes {
    fun hasBluetoothMedia(context: Context): Boolean = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return false
        anyBluetoothMedia(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type })
    }.getOrDefault(false)

    /** SCO (calls) does not carry TTS media; only A2DP counts. */
    fun anyBluetoothMedia(outputTypes: List<Int>): Boolean = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in outputTypes
}
