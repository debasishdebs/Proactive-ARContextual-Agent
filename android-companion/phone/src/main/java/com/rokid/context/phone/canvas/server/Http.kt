package com.rokid.context.phone.canvas.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Our own request/response types: routing, auth and JSON are written against these, so the NanoHTTPD adapter
 * ([CanvasServer]) is the only class that knows the HTTP library.
 *
 * [headers] keys are lower-case. [bodyTooLarge] is set by the adapter when the declared content length
 * exceeded [CanvasServer.MAX_BODY] and the body was therefore not read.
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
    val bodyTooLarge: Boolean = false,
    /** The peer address of the connection (the adapter sets it); null in unit tests. Used only to rate-limit pairing per device. */
    val remoteIp: String? = null,
)

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        const val JSON = "application/json; charset=utf-8"

        fun json(status: Int, body: JsonElement, headers: Map<String, String> = emptyMap()) =
            HttpResponse(status, JSON, body.toString().toByteArray(Charsets.UTF_8), headers)

        fun error(status: Int, code: String, message: String, problems: List<String> = emptyList()) =
            json(
                status,
                buildJsonObject {
                    put("error", code)
                    put("message", message)
                    put("problems", JsonArray(problems.map { JsonPrimitive(it) }))
                },
            )
    }
}
