package com.rokid.context.model.provider

import com.rokid.context.memory.place.ZoneFenced
import com.rokid.context.memory.place.currentZoneTicket
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Executes [request] on [client]. Inside a Place Guide attempt (a [com.rokid.context.memory.place.ZoneTicket] in the context) the call is fenced: registering the cancel hook IS the
 * admission check and is the last step before `execute()`, so a zone trip during payload construction throws [ZoneFenced] before any byte is sent, and a trip after it cancels the
 * in-flight call. Outside an attempt nothing changes. [beforeAdmit] is a test seam run just before admission.
 */
internal suspend fun executeFenced(client: OkHttpClient, request: Request, beforeAdmit: () -> Unit = {}): Response {
    val ticket = currentZoneTicket() ?: return client.newCall(request).execute()
    val call: Call = client.newCall(request)
    beforeAdmit()
    val reg = ticket.onClose { call.cancel() }
    try {
        return call.execute()
    } catch (e: IOException) {
        if (!ticket.isOpen()) throw ZoneFenced() else throw e
    } finally { reg.close() }
}
