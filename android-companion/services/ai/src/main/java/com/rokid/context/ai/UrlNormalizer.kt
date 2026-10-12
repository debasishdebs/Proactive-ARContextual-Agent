package com.rokid.context.ai

import java.net.URI

/**
 * Review Focus #2 (Phase 2): messy endpoint input is normalised or rejected with a short reason; never stored broken.
 * Plain http is allowed only without a key and only for [CLEARTEXT_HOSTS] / [CLEARTEXT_SUFFIXES], which must equal
 * `phone/src/main/res/xml/network_security_config.xml` (CleartextConfigTest). Android's config cannot express IP
 * ranges, so a LAN server is reached by its `.local` / `.lan` name (or over https).
 */
object UrlNormalizer {
    val CLEARTEXT_HOSTS = listOf("localhost", "127.0.0.1", "10.0.2.2")
    val CLEARTEXT_SUFFIXES = listOf("local", "lan", "home.arpa")
    private const val NOT_LOCAL = "Plain http:// works only for localhost or a .local / .lan name — use https://"

    fun allowsCleartext(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h in CLEARTEXT_HOSTS || CLEARTEXT_SUFFIXES.any { h == it || h.endsWith(".$it") }
    }

    fun normalize(raw: String, kind: EndpointKind, hasKey: Boolean = false): Result<String> = runCatching {
        val s = raw.trim()
        require(s.isNotEmpty()) { "Enter an address" }
        if (kind == EndpointKind.LLAMACPP_LOCAL) return@runCatching s
        require(s.none { it.isWhitespace() }) { "Address can't contain a space" }
        val withScheme = if ("://" in s) s else {
            val localGuess = runCatching { URI("http://$s").host }.getOrNull()?.let(::allowsCleartext) == true
            (if (kind == EndpointKind.OLLAMA && localGuess && !hasKey) "http://" else "https://") + s
        }
        val uri = URI(withScheme)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") { "Address must start with http:// or https://" }
        val host = uri.host?.lowercase()?.trimEnd('.')
        require(!host.isNullOrBlank()) { "Address is missing a host" }
        if (scheme == "http") {
            require(kind != EndpointKind.AZURE_OPENAI) { "Azure needs https://" }
            require(!hasKey) { "Use https:// when sending a key" }
            require(allowsCleartext(host)) { NOT_LOCAL }
        }
        val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        buildString {
            append(scheme).append("://").append(host)
            if (port != null) append(':').append(port)
            append(path)
        }
    }.recoverCatching { e -> throw IllegalArgumentException(if (e is IllegalArgumentException) e.message else "That doesn't look like a web address") }

    /** ASCII printable only: invisible, smart or non-Latin characters are a paste accident, never part of a key. */
    fun cleanKey(raw: String): Result<String> = runCatching {
        val k = raw.trim()
        require(k.isNotEmpty()) { "Enter a key" }
        require(k.none { it.isWhitespace() }) { "Key can't contain spaces" }
        require(k.all { it in '!'..'~' }) { "Key has invalid characters" }
        k
    }
}
