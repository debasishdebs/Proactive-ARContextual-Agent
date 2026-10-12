package com.rokid.context.glasses.link

import java.util.concurrent.ConcurrentHashMap

/**
 * One CXR bridge per process. CXR-S has no unsubscribe or close, so every channel is subscribed on [inner] once and
 * each message goes to whoever subscribed last (the current activity's controller); [release] silences the handlers
 * when that controller goes away, so a destroyed activity is never called back.
 */
class SharedBridge(private val inner: GlassesBridge) : GlassesBridge {
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()
    private val subscribed = HashSet<String>()
    @Volatile private var connected: () -> Unit = {}
    @Volatile private var disconnected: () -> Unit = {}
    private var listening = false

    @Synchronized override fun subscribe(channel: String, onText: (String) -> Unit) {
        handlers[channel] = onText
        if (subscribed.add(channel)) inner.subscribe(channel) { handlers[channel]?.invoke(it) }
    }

    override fun send(channel: String, vararg parts: String): Int = inner.send(channel, *parts)

    @Synchronized override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        this.connected = connected
        this.disconnected = disconnected
        if (!listening) {
            listening = true
            inner.setConnectionListener(connected = { this.connected() }, disconnected = { this.disconnected() })
        }
    }

    @Synchronized fun release() {
        handlers.clear()
        connected = {}
        disconnected = {}
    }
}
