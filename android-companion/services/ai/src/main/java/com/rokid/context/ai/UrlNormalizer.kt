package com.rokid.context.ai

import java.net.URI

/** Review Focus #2: messy endpoint input is normalised or rejected with a short reason; never stored broken. */
object UrlNormalizer {
    fun normalize(raw: String, kind: EndpointKind, hasKey: Boolean = false): Result<String> = runCatching {
        val s = raw.trim()
        require(s.isNotEmpty()) { "Enter an address" }
        if (kind == EndpointKind.LLAMACPP_LOCAL) return@runCatching s
        require(s.none { it.isWhitespace() }) { "Address can't contain a space" }
        val withScheme = if ("://" in s) s else (if (kind == EndpointKind.OLLAMA) "http://" else "https://") + s
        val uri = URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Address must start with http:// or https://" }
        val host = uri.host?.lowercase()
        require(!host.isNullOrBlank()) { "Address is missing a host" }
        if (scheme == "http") {
            require(kind != EndpointKind.AZURE_OPENAI) { "Azure needs https://" }
            require(kind != EndpointKind.OPENAI_COMPAT || !hasKey || isLocalHost(host)) { "Use https:// when sending a key" }
        }
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(path)
        }
    }.recoverCatching { e -> throw IllegalArgumentException(if (e is IllegalArgumentException) e.message else "That doesn't look like a web address") }

    fun cleanKey(raw: String): Result<String> = runCatching {
        val k = raw.trim()
        require(k.isNotEmpty()) { "Enter a key" }
        require(k.none { it.isWhitespace() }) { "Key can't contain spaces" }
        require(k.all { it in '!'..'~' }) { "Key has invalid characters" }
        k
    }

    /** Loopback, emulator host, private LAN IPv4 literals and mDNS ".local" names: cleartext never leaves the local network. */
    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host == "[::1]" || host == "10.0.2.2" || host.endsWith(".local")) return true
        val o = host.split('.').map { it.toIntOrNull() ?: return false }
        if (o.size != 4 || o.any { it !in 0..255 }) return false
        return o[0] == 127 || o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }
}
