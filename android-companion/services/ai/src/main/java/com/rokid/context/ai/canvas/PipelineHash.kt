package com.rokid.context.ai.canvas

import java.security.MessageDigest

object PipelineHash {
    fun of(p: Pipeline): String =
        MessageDigest.getInstance("SHA-256").digest(PipelineJson.canonical(p).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun short(h: String) = h.take(12)
}
