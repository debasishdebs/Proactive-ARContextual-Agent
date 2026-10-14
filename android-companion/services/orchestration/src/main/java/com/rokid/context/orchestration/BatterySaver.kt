package com.rokid.context.orchestration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Android Battery Saver (G7 ruling, 2026-10-15: it pauses everything, user asks included). [isOn] is a cheap binder
 * read, safe on Main; [observe] follows ACTION_POWER_SAVE_MODE_CHANGED and starts with the current value.
 */
object BatterySaver {
    fun isOn(context: Context): Boolean = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { trySend(isOn(app)) }
        }
        app.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        trySend(isOn(app))
        awaitClose { runCatching { app.unregisterReceiver(receiver) } }
    }.distinctUntilChanged()
}
