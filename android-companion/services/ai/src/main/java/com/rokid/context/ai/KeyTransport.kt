package com.rokid.context.ai

object KeyTransport {
    /** A key is never sent over http, even for a row stored before the save-time rule existed. */
    fun requireSecure(url: String, key: String?) {
        if (!key.isNullOrBlank() && url.trim().startsWith("http://", ignoreCase = true))
            throw ProviderConfigException("A key is never sent over http")
    }
}
