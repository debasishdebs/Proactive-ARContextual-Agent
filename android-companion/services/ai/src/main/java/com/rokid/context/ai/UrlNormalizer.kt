package com.rokid.context.ai

import java.net.URI

/** Review Focus #2: messy endpoint input is normalised or rejected with a short reason; never stored broken. */
object UrlNormalizer {
    fun normalize(raw: String, kind: EndpointKind): Result<String> = runCatching {
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
        k
    }
}
