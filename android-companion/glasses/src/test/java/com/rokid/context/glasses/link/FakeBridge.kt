package com.rokid.context.glasses.link

/** Records sends; [phone] plays a phone → glasses message. */
class FakeBridge : GlassesBridge {
    private val subs = mutableMapOf<String, (String) -> Unit>()
    val sent = mutableListOf<Pair<String, List<String>>>()
    var rc = 0
    var connected: () -> Unit = {}
        private set
    var disconnected: () -> Unit = {}
        private set

    override fun subscribe(channel: String, onText: (String) -> Unit) { subs[channel] = onText }
    override fun send(channel: String, vararg parts: String): Int { sent += channel to parts.toList(); return rc }
    override fun setConnectionListener(connected: () -> Unit, disconnected: () -> Unit) {
        this.connected = connected
        this.disconnected = disconnected
    }
    fun phone(channel: String, text: String) = subs.getValue(channel)(text)
}
