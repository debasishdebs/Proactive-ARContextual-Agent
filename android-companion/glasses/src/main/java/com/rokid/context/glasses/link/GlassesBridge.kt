package com.rokid.context.glasses.link

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/** The CXR-S service bridge as the HUD uses it; [CxrBridge] in the app, a fake in tests. */
interface GlassesBridge {
    /** [onText] gets the first Caps string of each message on [channel] (called on a bridge thread). */
    fun subscribe(channel: String, onText: (String) -> Unit)
    /** CXR-S `sendMessage` code: 0 = handed to the CXR service, NOT delivered (phase 0); negative = refused. */
    fun send(channel: String, vararg parts: String): Int
    fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit)
}

class CxrBridge(context: Context) : GlassesBridge {
    private val bridge = CXRServiceBridge(context)

    override fun subscribe(channel: String, onText: (String) -> Unit) {
        bridge.subscribe(channel, CXRServiceBridge.MsgCallback { _, caps, _ ->
            runCatching { caps?.at(0)?.string }.getOrNull()?.let(onText)
        })
    }

    override fun send(channel: String, vararg parts: String): Int = try {
        bridge.sendMessage(channel, Caps().apply { parts.forEach { write(it) } })
    } catch (e: Exception) {
        PcLog.w(HudController.TAG, "send $channel threw ${e.javaClass.simpleName}")
        -3
    }

    override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, mac: String?, type: Int) = connected()
            override fun onDisconnected() = disconnected()
            override fun onConnecting(name: String?, mac: String?, type: Int) = Unit
            override fun onARTCStatus(p0: Float, p1: Boolean) = Unit
            override fun onRokidAccountChanged(p0: String?) = Unit
            override fun onAudioNoise(p0: Float) = Unit
        })
    }
}
