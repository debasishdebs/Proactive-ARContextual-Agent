package com.rokid.context.phone.canvas.server

/**
 * Host and Origin allow-list (defeats DNS rebinding and cross-site POSTs).
 * [lanIp] is the current Wi-Fi IPv4 while "Use from a laptop" is on, otherwise null.
 */
class HostPolicy(private val port: () -> Int, private val lanIp: () -> String?) {
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
        return origin == null || (host != null && origin == "http://$host")
    }
}
