package com.rokid.context.prompt

import java.security.MessageDigest

object PromptHash {
    fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
