package com.rokid.context.model.provider

import com.rokid.context.memory.place.ZoneFenced
import com.rokid.context.memory.place.currentZoneTicket
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The data saver's last look before a connection is opened (Codex r2 P1-1): [check] throws (a `DataSaverBlockedException`) when the policy no longer allows this request on the network the phone is on RIGHT NOW.
 * Put on the coroutine by the router; [executeFenced] runs it immediately before `execute()`. Nothing is sent when it throws.
 */
internal class TransportGuard(val check: () -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransportGuard>
}

/**
 * Executes [request] on [client]. Inside a Place Guide attempt (a [com.rokid.context.memory.place.ZoneTicket] in the context) the call is fenced: registering the cancel hook IS the
 * admission check and is the last step before `execute()`, so a zone trip during payload construction throws [ZoneFenced] before any byte is sent, and a trip after it cancels the
 * in-flight call. [beforeAdmit] is a test seam run just before admission.
 *
 * Data saver (Codex r2 P1-1): the calling coroutine's cancellation is wired to [Call.cancel] (the moment the job starts cancelling, not when the blocking call returns) and stays wired through the
 * consumption of the response body (it is released when the body is closed), so cancelling a request aborts a running upload or download at the socket. A [TransportGuard] in the context is asked right before the connect.
 */
@OptIn(InternalCoroutinesApi::class)
internal suspend fun executeFenced(client: OkHttpClient, request: Request, beforeAdmit: () -> Unit = {}): Response {
    val ticket = currentZoneTicket()
    val guard = coroutineContext[TransportGuard]
    val job = coroutineContext[Job]
    if (ticket == null && job == null && guard == null) return client.newCall(request).execute()
    val call: Call = client.newCall(request)
    beforeAdmit()
    val reg = ticket?.onClose { call.cancel() }
    val jobReg = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { call.cancel() }
    fun release() { reg?.close(); jobReg?.dispose() }
    try {
        guard?.check() // the network class right before the connect: a refusal leaves before any byte
        val response = call.execute()
        val body = response.body ?: run { release(); return response }
        return response.newBuilder().body(ReleasingBody(body, job, ::release)).build()
    } catch (e: IOException) {
        release()
        job?.ensureActive() // a call cancelled because its coroutine was cancelled ends as a cancellation, not as an IO failure
        if (ticket != null && !ticket.isOpen()) throw ZoneFenced() else throw e
    } catch (t: Throwable) {
        release(); throw t
    }
}

/** A read that fails because the calling coroutine was cancelled (and with it the call) surfaces as a cancellation, not as an IO failure. */
private class CancelAwareSource(private val job: Job?, delegate: Source) : ForwardingSource(delegate) {
    override fun read(sink: Buffer, byteCount: Long): Long = try { super.read(sink, byteCount) } catch (e: IOException) { job?.ensureActive(); throw e }
}

/** A response body that releases the cancellation wiring when it is closed (so the call stays cancellable for the whole read). */
private class ReleasingBody(private val inner: ResponseBody, private val job: Job?, private val onClose: () -> Unit) : ResponseBody() {
    override fun contentType() = inner.contentType()
    override fun contentLength() = inner.contentLength()
    private val src: BufferedSource by lazy { CancelAwareSource(job, inner.source()).buffer() }
    override fun source(): BufferedSource = src
    override fun close() { try { inner.close() } finally { onClose() } }
}
