package com.rokid.context.phone.canvas.server

/**
 * Host and Origin allow-list (defeats DNS rebinding and cross-site POSTs).
 * [lanIp] is the current Wi-Fi IPv4 while "Use from a laptop" is on, otherwise null. [secure] is true while the server speaks
 * HTTPS (LAN on): the Origin of a state-changing request must then be `https://<Host>`, and an `http://` Origin is refused.
 * The Host rule does not depend on the scheme.
 */
class HostPolicy(private val port: () -> Int, private val lanIp: () -> String?, private val secure: () -> Boolean = { false }) {
    fun hostAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        val p = port()
        if (h == "127.0.0.1:$p" || h == "localhost:$p") return true
        val lan = lanIp() ?: return false
        return h == "${lan.lowercase()}:$p"
    }

    fun originAllowed(method: String, origin: String?, host: String?): Boolean {
        val m = method.uppercase()
        if (m == "GET" || m == "HEAD") return true
        return origin == null || (host != null && origin == "${if (secure()) "https" else "http"}://$host")
    }
}
