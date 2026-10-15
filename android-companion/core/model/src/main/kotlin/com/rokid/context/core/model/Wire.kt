package com.rokid.context.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface Decoded<out T> {
    data class Ok<T>(val value: T) : Decoded<T>
    /** [remote] is the sender's `v`, or null when absent. */
    data class VersionMismatch(val remote: Int?) : Decoded<Nothing>
    /** [reason] is a fixed text or an exception class name, never the exception message: that can quote the payload. */
    data class Malformed(val reason: String) : Decoded<Nothing>
}

object Wire {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true // an unknown enum string falls back to the property default (MotionState.UNKNOWN, CaptureMode.FIXED)
        classDiscriminator = "kind"
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    /** Checks `v` before decoding so a peer on another version never half-applies a message. */
    inline fun <reified T> decode(text: String): Decoded<T> {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { return Decoded.Malformed("not a JSON object") }
        val v = runCatching { obj["v"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (v != PROTOCOL_VERSION) return Decoded.VersionMismatch(v)
        return runCatching { Decoded.Ok(json.decodeFromJsonElement<T>(obj)) }
            .getOrElse { Decoded.Malformed(it.javaClass.simpleName) }
    }
}
