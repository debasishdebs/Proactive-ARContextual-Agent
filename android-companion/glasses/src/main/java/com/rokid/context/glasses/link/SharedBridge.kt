package com.rokid.context.glasses.link

import java.util.concurrent.ConcurrentHashMap

/**
 * One CXR bridge per process. CXR-S has no unsubscribe or close, so every channel is subscribed on [inner] once and
 * each message goes to whoever subscribed last (the current activity's controller); [release] silences the handlers
 * when that controller goes away, so a destroyed activity is never called back. The last connected/disconnected
 * callback is kept across [release] and replayed to each new listener at once (Phase 8 C4), so a controller made after
 * the bridge connected never waits for a callback CXR-S already sent.
 */
class SharedBridge(private val inner: GlassesBridge) : GlassesBridge {
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()
    private val subscribed = HashSet<String>()
    @Volatile private var connected: () -> Unit = {}
    @Volatile private var disconnected: () -> Unit = {}
    private var listening = false
    /** The last inner callback: true connected, false disconnected, null none yet. */
    @Volatile private var lastConnected: Boolean? = null

    @Synchronized override fun subscribe(channel: String, onText: (String) -> Unit) {
        handlers[channel] = onText
        if (subscribed.add(channel)) inner.subscribe(channel) { handlers[channel]?.invoke(it) }
    }

    override fun send(channel: String, vararg parts: String): Int = inner.send(channel, *parts)

    override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        val replay = synchronized(this) {
            this.connected = connected
            this.disconnected = disconnected
            if (!listening) {
                listening = true
                inner.setConnectionListener(
                    connected = { lastConnected = true; this.connected() },
                    disconnected = { lastConnected = false; this.disconnected() },
                )
            }
            lastConnected
        }
        when (replay) {
            true -> connected()
            false -> disconnected()
            null -> Unit
        }
    }

    @Synchronized fun release() {
        handlers.clear()
        connected = {}
        disconnected = {}
    }
}
