package com.rokid.context.glasses.link

import java.util.concurrent.CopyOnWriteArrayList

/** Records sends; [phone] plays a phone → glasses message. */
class FakeBridge : GlassesBridge {
    private val subs = mutableMapOf<String, (String) -> Unit>()
    /** Thread-safe: the push-to-talk thread appends audio while tests read it (preflight F11). */
    val sent = CopyOnWriteArrayList<Pair<String, List<String>>>()
    /** Every subscribe call, in order (the real SDK has no unsubscribe). */
    val subscriptions = mutableListOf<String>()
    var rc = 0
    var connected: () -> Unit = {}
        private set
    var disconnected: () -> Unit = {}
        private set

    override fun subscribe(channel: String, onText: (String) -> Unit) { subscriptions += channel; subs[channel] = onText }
    override fun send(channel: String, vararg parts: String): Int { sent += channel to parts.toList(); return rc }
    override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        this.connected = connected
        this.disconnected = disconnected
    }
    fun phone(channel: String, text: String) = subs.getValue(channel)(text)
}
