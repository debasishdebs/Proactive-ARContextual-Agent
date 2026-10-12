package com.rokid.context.ai

object KeyTransport {
    /** Tests only: lets a plain-http MockWebServer stand in for an https endpoint. */
    @Volatile internal var allowCleartextKeyForTests = false

    /** A key is never sent over http, even for a row stored before the save-time rule existed. */
    fun requireSecure(url: String, key: String?) {
        if (!key.isNullOrBlank() && !allowCleartextKeyForTests && url.trim().startsWith("http://", ignoreCase = true))
            throw ProviderConfigException("A key is never sent over http")
    }
}
