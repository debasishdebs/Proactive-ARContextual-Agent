package com.rokid.context.phone.hub

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the phone is being used right now: screen on and not locked. Feeds `pc_phone_ctx` (battery wave A §7). */
class PhoneUseSignal {
    private val _inUse = MutableStateFlow(false)
    val inUse: StateFlow<Boolean> = _inUse

    /** The pure core: in use = interactive and not behind the keyguard. */
    fun onScreen(interactive: Boolean, keyguardLocked: Boolean) { _inUse.value = interactive && !keyguardLocked }

    private var app: Context? = null
    private var receiver: BroadcastReceiver? = null

    /** Registers the screen receivers on the application context and reads the current state. Idempotent. */
    fun start(context: Context) {
        if (receiver != null) return
        val a = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = read(a)
        }
        app = a; receiver = r
        a.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        read(a)
    }

    fun stop() {
        val r = receiver ?: return
        runCatching { app?.unregisterReceiver(r) }
        receiver = null; app = null
    }

    private fun read(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = c.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        onScreen(pm?.isInteractive ?: false, km?.isKeyguardLocked ?: false)
    }
}
