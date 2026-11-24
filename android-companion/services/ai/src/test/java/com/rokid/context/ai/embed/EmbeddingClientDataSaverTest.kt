package com.rokid.context.ai.embed

import com.rokid.context.ai.Capability
import com.rokid.context.ai.DataSaverBlockedException
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codex data-saver r1 P1-2: remote embedding batches go through the data saver (admission by reach and kind) and are counted. */
class EmbeddingClientDataSaverTest {
    private class Gate(var allow: Boolean = true) : DataGate {
        val asked = mutableListOf<Pair<DataKind, EndpointReach>>()
        val sentLog = mutableListOf<List<Any>>()
        val skippedLog = mutableListOf<Pair<DataKind, String>>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision { asked += kind to reach; return if (allow || reach == EndpointReach.DEVICE) DataDecision.ALLOW else DataDecision(false, "roaming") }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) { skippedLog += kind to d.reason }
        override fun costlyNow() = true
        override fun sentAt(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int, costlyAtStart: Boolean) { sentLog += listOf(kind, txBytes, rxBytes, photos, costlyAtStart) }
    }

    private var requests = 0
    private val reply = """{"data":[{"index":0,"embedding":[1,2]},{"index":1,"embedding":[3,4]}]}"""
    private var failWith: java.io.IOException? = null
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        requests++
        failWith?.let { throw it }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(reply.toResponseBody("application/json".toMediaType())).build()
    }).build()
    private val gate = Gate()
    private val client = EmbeddingClient(http, dataGate = gate)
    private fun remote(url: String = "https://embed.example.com") = Endpoint("e", "E", EndpointKind.OPENAI_COMPAT, url, null, listOf("m"), setOf(Capability.EMBEDDINGS), 1)
    private val batch = List(5) { "memory text number $it" }

    @Test fun aRemoteBatchIsRefusedBeforeAnythingIsSentWhenTheSaverSaysNo() = runTest {
        gate.allow = false
        val e = runCatching { client.embed(remote(), "m", "k", batch) }.exceptionOrNull()
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertEquals(0, requests)
        assertEquals(listOf(DataKind.BULK_OTHER to "roaming"), gate.skippedLog)
        assertTrue("already counted: the router must not count it again", (e as DataSaverBlockedException).counted)
    }

    @Test fun aBatchIsBulkAndAQueryIsSmall() = runTest {
        client.embed(remote(), "m", "k", batch.take(2).let { listOf(it[0], it[1]) })
        client.embed(remote(), "m", "k", listOf("one query", "and another"))
        gate.asked.clear()
        runCatching { client.embed(remote(), "m", "k", batch) }
        assertEquals(listOf(DataKind.BULK_OTHER to EndpointReach.REMOTE), gate.asked.distinct()) // the watcher re-asks while a call runs: same question
        gate.asked.clear()
        client.embed(remote(), "m", "k", listOf("a", "b"))
        assertEquals(listOf(DataKind.SMALL to EndpointReach.REMOTE), gate.asked.distinct())
    }

    @Test fun requestAndResponseBytesAreCountedForTheKindAndTheNetworkAtStart() = runTest {
        client.embed(remote(), "m", "k", listOf("a", "b"))
        assertEquals(1, gate.sentLog.size)
        val (kind, tx, rx, photos, costly) = gate.sentLog[0].let { listOf(it[0], it[1], it[2], it[3], it[4]) }
        assertEquals(DataKind.SMALL, kind); assertTrue("the request body: $tx", (tx as Long) > 20); assertEquals(reply.toByteArray().size.toLong(), rx); assertEquals(0, photos); assertEquals(true, costly)
    }

    @Test fun aStalledBatchStillCountsItsRequestButAConnectFailureDoesNot() = runTest {
        failWith = java.net.SocketTimeoutException("read timed out")
        runCatching { client.embed(remote(), "m", "k", batch) }
        assertEquals("the request may have been received", 1, gate.sentLog.size)
        gate.sentLog.clear()
        failWith = java.net.ConnectException("refused")
        runCatching { client.embed(remote(), "m", "k", batch) }
        assertTrue("nothing left the phone", gate.sentLog.isEmpty())
    }

    @Test fun aLoopbackEndpointIsAskedAsLanButNeverCounted() = runTest {
        client.embed(remote("https://127.0.0.1:11434"), "m", "k", listOf("a", "b"))
        assertTrue(gate.sentLog.isEmpty())
        assertEquals(EndpointReach.LAN, gate.asked.single().second)
    }

    @Test fun anOnDeviceEmbeddingModelIsNeverGatedOrCounted() = runTest {
        val local = Endpoint("l", "L", EndpointKind.LLAMACPP_LOCAL, "model.gguf", null, listOf("m"), setOf(Capability.EMBEDDINGS), 1)
        val c = EmbeddingClient(http, native = { _, t, _ -> t.map { floatArrayOf(1f, 0f) } }, dataGate = gate.also { it.allow = false })
        c.embed(local, "m", null, batch)
        assertTrue(gate.asked.isEmpty()); assertTrue(gate.sentLog.isEmpty())
    }

    // ---- Codex data-saver r2 P1-6: the shared durable admission and the cancellable transport ----

    private class ReservingGate : DataGate {
        var allow = true
        var failReserve = false
        val events = mutableListOf<String>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision = if (allow) DataDecision.ALLOW else DataDecision(false, "roaming")
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) {}
        override fun costlyNow() = true
        override fun reserve(kind: DataKind, txBytes: Long, photos: Int): com.rokid.context.core.net.DataReservation { events += "reserve"; return if (failReserve) com.rokid.context.core.net.DataReservation.FAILED else com.rokid.context.core.net.DataReservation(kind, txBytes, photos, 1) }
        override fun settle(r: com.rokid.context.core.net.DataReservation, txBytes: Long, rxBytes: Long, photos: Int) { events += "settle:$txBytes:$rxBytes" }
    }

    @Test fun aCostlyBatchTakesItsReservationBeforeTheRequestLeavesAndSettlesAfter() = kotlinx.coroutines.runBlocking {
        val g = ReservingGate()
        var reservedWhenSent = false
        val c = EmbeddingClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            reservedWhenSent = g.events == listOf("reserve")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(reply.toResponseBody("application/json".toMediaType())).build()
        }).build(), dataGate = g)
        c.embed(remote(), "m", "k", listOf("a", "b"))
        assertTrue("reserved before the request left", reservedWhenSent)
        assertEquals(2, g.events.size); assertTrue(g.events[1].startsWith("settle:"))
    }

    @Test fun aReservationThatCannotBeMadeDurableRefusesTheBatch() = kotlinx.coroutines.runBlocking {
        val g = ReservingGate().also { it.failReserve = true }
        val c = EmbeddingClient(http, dataGate = g)
        val e = runCatching { c.embed(remote(), "m", "k", batch) }.exceptionOrNull()
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertEquals("nothing was sent", 0, requests)
    }

    @Test fun theNetworkTurningCostlyUnderARunningBatchAbortsItAtTheSocket() = kotlinx.coroutines.runBlocking {
        val g = ReservingGate()
        val c = EmbeddingClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            g.allow = false // the phone moved to roaming right after the admission
            val t0 = System.nanoTime()
            while (!chain.call().isCanceled() && System.nanoTime() - t0 < 8_000_000_000L) Thread.sleep(20)
            throw java.io.IOException("Canceled")
        }).build(), dataGate = g, watchMs = 50L)
        // the pre-connect check already sees allow=true (the interceptor runs after it); the watcher then sees false and cancels the call
        val t0 = System.nanoTime()
        val e = runCatching { c.embed(remote(), "m", "k", batch) }.exceptionOrNull()
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertTrue("aborted long before the 8 s stall", (System.nanoTime() - t0) < 6_000_000_000L)
        assertTrue("the conservative reservation was settled with the request bytes", g.events.last().startsWith("settle:") && !g.events.last().startsWith("settle:0:"))
    }

    @Test fun theCurrentNetworkIsCheckedRightBeforeTheConnect() = kotlinx.coroutines.runBlocking {
        val g = ReservingGate()
        val c = EmbeddingClient(http, dataGate = g)
        // admission passes, then the network turns costly before the connect: flip the gate when the reservation is taken
        val flipping = object : DataGate by g {
            override fun reserve(kind: DataKind, txBytes: Long, photos: Int) = g.reserve(kind, txBytes, photos).also { g.allow = false }
        }
        val e = runCatching { EmbeddingClient(http, dataGate = flipping).embed(remote(), "m", "k", batch) }.exceptionOrNull()
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertEquals("not a byte left", 0, requests)
        assertEquals("the reservation was released (nothing sent)", "settle:0:0", g.events.last())
    }
}
