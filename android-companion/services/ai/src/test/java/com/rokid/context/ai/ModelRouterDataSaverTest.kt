package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelRequest
import com.rokid.context.core.net.SendOnce
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The roaming data saver at the router's choke point: a pure pre-check before the permit. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelRouterDataSaverTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val endpoints = EndpointRepository(db.endpointDao(), InMemoryKeyVault())
    private val bindings = RoleBindingRepository(db.roleBindingDao(), endpoints)
    private val providerCalls = mutableListOf<String>()
    private val permits = mutableListOf<CallClass>()
    private val failing = mutableSetOf<String>()
    private var httpFailure = false
    private var failWith: Throwable? = null
    private var onPermit: () -> Unit = {}
    private var onGenerate: () -> Unit = {}
    private var onVision: suspend () -> Unit = {}
    private val callGate = object : CallGate {
        override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T { permits += callClass; onPermit(); return block() }
    }
    private val providers = ProviderBuilder { e, model, _ ->
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { providerCalls += "${e.id}:text"; onGenerate(); failWith?.let { throw it }; if (e.id in failing) error("boom"); return "${e.id}/$model" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { providerCalls += "${e.id}:vision"; onVision(); failWith?.let { throw it }; if (httpFailure) throw ProviderHttpException(500, "x", ""); return "${e.id}/vision" }
        }
    }

    /** Blocks per kind and reach; records what it was asked and what was skipped. */
    private class FakeGate(var blockPhoto: Boolean = false, var blockSmall: Boolean = false) : DataGate {
        val asked = mutableListOf<Pair<DataKind, EndpointReach>>()
        val skippedLog = mutableListOf<Triple<DataKind, Skipped, String>>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision {
            asked += kind to reach
            if (reach == EndpointReach.DEVICE) return DataDecision(true, "on_device")
            if (reach == EndpointReach.LAN && lanFree) return DataDecision(true, "lan")
            val block = if (kind == DataKind.PHOTO) blockPhoto else blockSmall
            return if (block) DataDecision(false, "wifi_only") else DataDecision.ALLOW
        }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) { skippedLog += Triple(kind, what, d.reason) }
        /** (kind, txBytes, rxBytes, photos) of every call that went out (slice 2 byte counter). */
        val sentLog = mutableListOf<List<Any>>()
        override fun sent(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int) { sentLog += listOf(kind, txBytes, rxBytes, photos) }
        var costly = false
        var throwOnSent = false
        var sentAttempts = 0
        val costlyAtStartLog = mutableListOf<Boolean>()
        var costlyCalls = 0
        override fun costlyNow() = costly.also { costlyCalls++ }
        override fun sentAt(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int, costlyAtStart: Boolean) {
            sentAttempts++; costlyAtStartLog += costlyAtStart
            if (throwOnSent) error("ledger down")
            sent(kind, txBytes, rxBytes, photos)
        }
        /** Reservations (P1-6): when [reserving], every costly call takes one up front and settles it afterwards. */
        var reserving = false
        var failReserve = false
        var lanFree = false
        val reserveThreads = mutableListOf<Thread>()
        val reservations = mutableListOf<List<Any>>()
        val settlements = mutableListOf<List<Any>>()
        override fun reserve(kind: DataKind, txBytes: Long, photos: Int): com.rokid.context.core.net.DataReservation? {
            reserveThreads += Thread.currentThread()
            if (!reserving || !costly) return null
            if (failReserve) return com.rokid.context.core.net.DataReservation.FAILED
            reservations += listOf(kind, txBytes, photos)
            return com.rokid.context.core.net.DataReservation(kind, txBytes, photos, 1)
        }
        override fun settle(r: com.rokid.context.core.net.DataReservation, txBytes: Long, rxBytes: Long, photos: Int) { settlements += listOf(r.kind, txBytes, rxBytes, photos) }
        var onceAsked = 0
        var allowOnce = false
        override fun decideOnce(kind: DataKind, reach: EndpointReach): DataDecision { onceAsked++; return if (allowOnce) DataDecision(true, "send_once") else decide(kind, reach) }
    }

    private val data = FakeGate()
    private val router = ModelRouter(bindings, endpoints, providers, callGate, data)
    @After fun close() = db.close()

    private suspend fun ep(id: String, kind: EndpointKind = EndpointKind.OLLAMA, url: String = "https://example.com:1", vararg caps: Capability) =
        endpoints.save(Endpoint(id, id, kind, if (kind == EndpointKind.LLAMACPP_LOCAL) "model.gguf" else url, null, listOf("m"), caps.toSet(), 1), apiKey = null).getOrThrow()
    private val img = listOf(ModelImageInput("image/jpeg", "AAAA", ref = "f1"))
    private suspend fun err(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull()!!

    @Test fun aBlockedPhotoCallThrowsBeforeThePermitAndTheProvider() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.blockPhoto = true
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertEquals("wifi_only", (e as DataSaverBlockedException).reason)
        assertTrue("no token spent", permits.isEmpty())
        assertTrue("nothing sent", providerCalls.isEmpty())
        assertEquals(listOf(Triple(DataKind.PHOTO, Skipped.UPLOAD_QUEUED, "wifi_only")), data.skippedLog)
    }

    // ---- slice 2: the byte counter and "Send once" ----

    @Test fun aPhotoCallCountsItsRequestAndReplyBytesOnceAtTheChokePoint() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) }
        // prompt "q" (1) + Base64 "AAAA" (4) + 512 envelope; reply "v/vision" (8) + 512
        assertEquals(listOf(listOf(DataKind.PHOTO, 517L, 520L, 1)), data.sentLog)
    }

    @Test fun aTextCallCountsAsSmallWithNoPhotos() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }
        assertEquals(1, data.sentLog.size)
        assertEquals(DataKind.SMALL, data.sentLog[0][0]); assertEquals(512L + 5, data.sentLog[0][1]); assertEquals(0, data.sentLog[0][3])
    }

    @Test fun anOnDeviceModelAndABlockedCallCountNothing() = runTest {
        ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "loc", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertTrue("nothing leaves the phone", data.sentLog.isEmpty())
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.blockPhoto = true
        err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue("a blocked call sent nothing", data.sentLog.isEmpty())
    }

    @Test fun aFailedCallThatNeverReachedTheServerCountsNothingAndKeepsThePhotoCap() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        failWith = java.net.ConnectException("refused") // a connect failure: nothing was sent
        err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        assertTrue("no bytes for a request that did not go out", data.sentLog.isEmpty())
    }

    @Test fun anHttpErrorCountsItsRequestBytesButNeverAPhoto() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        httpFailure = true
        err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        assertEquals(listOf(listOf<Any>(DataKind.PHOTO, 517L, 0L, 0)), data.sentLog)
    }

    @Test fun aThrowingLedgerNeitherDiscardsTheReplyNorCountsTwice() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.throwOnSent = true
        val r = router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }
        assertEquals("a/m", r.text)
        assertEquals("one attempt at counting, no retry", 1, data.sentAttempts)
        assertEquals("a single provider call", listOf("a:text"), providerCalls)
    }

    @Test fun theNetworkClassIsReadOnceAtCallStartAndHandedToTheCounter() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.costly = true
        onGenerate = { data.costly = false } // the network changes while the call runs
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(listOf(true), data.costlyAtStartLog)
    }

    @Test fun aLanEndpointOnWifiIsNotCounted() = runTest {
        ep("lan", url = "http://mac.local:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        data.costly = false
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals("the ledger is told the call began on a free network, so it adds nothing", listOf(false), data.costlyAtStartLog)
    }

    @Test fun aLanEndpointOnMobileDataOrAVpnIsCounted() = runTest {
        ep("lan", url = "https://192.168.1.20:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        data.costly = true // mobile data, a LAN host reached through WireGuard/Tailscale
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(listOf(true), data.costlyAtStartLog)
        assertEquals(1, data.sentLog.size)
        assertEquals(512L + 1, data.sentLog[0][1])
    }

    @Test fun anOnDeviceModelIsNeverCountedNorEvenAskedForTheNetworkClass() = runTest {
        ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "loc", "m")).getOrThrow()
        data.costly = true
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(0, data.sentAttempts)
        assertTrue(data.sentLog.isEmpty())
        assertEquals("the network class is not even read", 0, data.costlyCalls)
    }

    @Test fun anEndpointOnThePhoneItselfIsNeverCountedEvenOnMobileData() = runTest {
        data.costly = true
        for (host in listOf("127.0.0.1", "127.1.2.3", "localhost", "[::1]")) {
            data.sentLog.clear(); data.asked.clear()
            ep("lo", url = "https://$host:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lo", "m")).getOrThrow()
            router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
            assertEquals(host, 0, data.sentAttempts)
            assertTrue(host, data.sentLog.isEmpty())
            assertEquals("the data-saver gate still sees it as LAN: $host (admission, then the re-check right before the send)", listOf(DataKind.SMALL to EndpointReach.LAN, DataKind.SMALL to EndpointReach.LAN), data.asked)
        }
    }

    @Test fun aLanBoxThatIsNotLoopbackIsStillCountedOnMobileData() = runTest {
        data.costly = true
        ep("lan", url = "https://192.168.1.5:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(1, data.sentAttempts)
        assertEquals(listOf(true), data.costlyAtStartLog)
    }

    @Test fun aReplyThatCannotBeUsedCountsItsRequestBytesButNoPhoto() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        for (t in listOf(ProviderResponseException("no content"), kotlinx.serialization.SerializationException("bad json"))) {
            data.sentLog.clear(); failWith = t
            err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
            assertEquals("$t", listOf(listOf<Any>(DataKind.PHOTO, 517L, 0L, 0)), data.sentLog)
        }
    }

    @Test fun connectAndDnsFailuresCountNothing() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        for (t in listOf(java.net.ConnectException("refused"), java.net.UnknownHostException("dns"))) {
            failWith = t
            err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        }
        assertTrue(data.sentLog.isEmpty())
    }

    @Test fun aTimeoutOrAnUnknownIoFailureAfterTheRequestMayHaveGoneOutCountsItConservatively() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        for (t in listOf(java.net.SocketTimeoutException("read timed out"), java.io.IOException("reset"), IllegalStateException("x"))) {
            data.sentLog.clear()
            failWith = t
            err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
            assertEquals(t.toString(), listOf(listOf<Any>(DataKind.SMALL, 513L, 0L, 0)), data.sentLog)
        }
    }

    @Test fun theNetworkClassIsReadAtThePermitNotBeforeTheWait() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.costly = false
        onPermit = { data.costly = true } // the network became costly while the call waited for its permit
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(listOf(true), data.costlyAtStartLog)
    }

    @Test fun aVisionAttemptAndItsTextRetryEachReadTheClassAtTheirOwnPermit() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.costly = true
        var n = 0
        onPermit = { data.costly = (n++ == 0) } // costly at the vision attempt's permit, free by the text retry's
        failWith = java.net.ConnectException("vision down") // the vision attempt fails with nothing counted; the text retry then succeeds
        onGenerate = { failWith = null }
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) }
        assertEquals("only the successful text retry is counted, with its own class", listOf(false), data.costlyAtStartLog)
        assertEquals(listOf("v:vision", "v:text"), providerCalls)
        assertEquals(2, permits.size)
    }

    @Test fun sendOnceIsHonouredOnlyForAUserClassPhotoCallCarryingTheMarker() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.blockPhoto = true; data.allowOnce = true
        // no marker: blocked, "once" is never even asked
        assertTrue(err { router.complete(Role.VISION_EXTRACT, CallClass.USER) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } } is DataSaverBlockedException)
        assertEquals(0, data.onceAsked)
        // marker but a background class: blocked, never asked
        assertTrue(err { withContext(SendOnce) { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } } } is DataSaverBlockedException)
        assertEquals(0, data.onceAsked)
        assertTrue(providerCalls.isEmpty())
        // marker and USER: goes out, counted
        val r = withContext(SendOnce) { router.complete(Role.VISION_EXTRACT, CallClass.USER) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        assertEquals("v/vision", r.text); assertEquals("asked at admission and again right before the send", 2, data.onceAsked); assertEquals(1, data.sentLog.size)
        // the marker is per call chain: the next call without it is blocked again
        assertTrue(err { router.complete(Role.VISION_EXTRACT, CallClass.USER) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } } is DataSaverBlockedException)
    }

    @Test fun sendOnceNeverAppliesToTextCalls() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.blockSmall = true; data.allowOnce = true
        assertTrue(err { withContext(SendOnce) { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } } is DataSaverBlockedException)
        assertEquals(0, data.onceAsked)
    }

    @Test fun aBlockedPhotoCallIsNotRetriedAsTextEvenWhenImagesAreOptional() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.blockPhoto = true
        // requireImages false: an ordinary vision failure would retry as text; a data-saver block must not
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e is DataSaverBlockedException)
        assertTrue(providerCalls.isEmpty() && permits.isEmpty())
        assertEquals(1, data.skippedLog.size)
    }

    @Test fun theFramesChainDoesNotTryTheSummarizeEndpointAfterABlock() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); ep("t", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow(); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        data.blockPhoto = true
        val e = err { router.completeFromFrames(CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e is DataSaverBlockedException)
        assertTrue("the text endpoint was not used as a way round the block", providerCalls.isEmpty())
    }

    @Test fun aBlockedPrimaryFallsBackOnlyToAnOnDeviceEndpoint() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION))
        ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "v", "m", fallbackEndpointId = "loc", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true
        val r = router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals("loc", r.endpoint.id)
        assertEquals(listOf(EndpointReach.REMOTE, EndpointReach.DEVICE), data.asked.map { it.second })
    }

    @Test fun aBlockedPrimaryWhoseOnDeviceFallbackFailsStillSurfacesAsTheBlock() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT)); ep("loc", kind = EndpointKind.LLAMACPP_LOCAL, caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "v", "m", fallbackEndpointId = "loc", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true; failing += "loc"
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        assertTrue("not a generic failure that a caller would count as an attempt: $e", e is DataSaverBlockedException)
    }

    @Test fun aCallBlockedOnPrimaryAndFallbackIsCountedOnce() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); ep("b", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } is DataSaverBlockedException)
        assertEquals("one call, one count", 1, data.skippedLog.size)
    }

    @Test fun theFramesChainCountsOnceToo() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); ep("t", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow(); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        data.blockPhoto = true
        assertTrue(err { router.completeFromFrames(CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } } is DataSaverBlockedException)
        assertEquals(1, data.skippedLog.size)
    }

    @Test fun aBlockedPrimaryWithARemoteFallbackIsBlockedToo() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); ep("b", caps = arrayOf(Capability.CHAT))
        bindings.set(RoleBinding(Role.ANSWER, "a", "m", fallbackEndpointId = "b", fallbackModel = "m2")).getOrThrow()
        data.blockSmall = true
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } } is DataSaverBlockedException)
        assertTrue(providerCalls.isEmpty())
    }

    @Test fun aBlockWithNoFallbackSurfacesAsTheBlockNotAGenericFailure() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.blockSmall = true
        val e = err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") } }
        assertTrue(e is DataSaverBlockedException)
        assertTrue((e as ModelCallException).retryable)
        assertEquals(listOf(Triple(DataKind.SMALL, Skipped.BULK, "wifi_only")), data.skippedLog)
    }

    @Test fun textCallsAreAskedAsSmallAndPhotoCallsAsPhoto() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) }
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q") }
        assertEquals(listOf(DataKind.PHOTO, DataKind.PHOTO, DataKind.SMALL, DataKind.SMALL), data.asked.map { it.first }) // each asked at admission and again right before the send
        assertEquals(listOf("v:vision", "v:text"), providerCalls)
    }

    @Test fun anEndpointThatCannotSendPhotosIsNotAPhotoCall() = runTest {
        ep("t", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.SUMMARIZE, "t", "m")).getOrThrow()
        data.blockPhoto = true // photos would be blocked, but this endpoint only gets the text
        router.complete(Role.SUMMARIZE, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) }
        assertEquals(listOf(DataKind.SMALL, DataKind.SMALL), data.asked.map { it.first })
    }

    @Test fun privateAddressesAreAskedAsLanAndTheHostIsNeverPassedOn() = runTest {
        ep("lan", url = "http://mybox.local:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        ep("ip", url = "https://192.168.1.20:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "ip", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        ep("cloud", url = "https://api.example.com", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "cloud", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }
        assertEquals(listOf(EndpointReach.LAN, EndpointReach.LAN, EndpointReach.LAN, EndpointReach.LAN, EndpointReach.REMOTE, EndpointReach.REMOTE), data.asked.map { it.second })
    }

    @Test fun theDefaultRouterAllowsEverything() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        assertFalse(ModelRouter(bindings, endpoints, providers).complete(Role.ANSWER, CallClass.USER) { ModelRequest("q") }.text.isEmpty())
    }

    // ---- Codex data-saver r1: P1-1 re-check at the send, P1-6 reservation ----

    @Test fun aPhotoAdmittedOnWifiThatWaitsForItsPermitIsDroppedUnsentWhenTheNetworkBecomesCostly() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        onPermit = { data.blockPhoto = true } // admitted on Wi-Fi; by the time the permit is granted the phone is on roaming
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertTrue("nothing was uploaded", providerCalls.isEmpty())
        assertTrue("and it was not retried as a text call either", data.sentLog.isEmpty())
    }

    @Test fun aRunningPhotoUploadIsAbortedWhenTheNetworkTurnsCostlyUnderIt() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.costly = true; data.reserving = true
        onVision = { data.blockPhoto = true; kotlinx.coroutines.delay(10 * 60_000L) } // the network changes while the server is slow
        val e = err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive) } }
        assertTrue(e.toString(), e is DataSaverBlockedException)
        assertEquals("the call stayed a photo call: one provider call, no text retry", listOf("v:vision"), providerCalls)
        assertEquals("an aborted upload keeps the conservative count: request bytes and its photo", listOf(listOf<Any>(DataKind.PHOTO, 517L, 0L, 1)), data.settlements)
    }

    @Test fun theReservationIsTakenBeforeTheRequestAndReconciledWithTheRealBytes() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.costly = true; data.reserving = true
        onVision = { assertEquals("reserved before any byte left", 1, data.reservations.size) }
        router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) }
        assertEquals(listOf(listOf<Any>(DataKind.PHOTO, 517L + 512L, 1)), data.reservations)
        assertEquals(listOf(listOf<Any>(DataKind.PHOTO, 517L, 520L, 1)), data.settlements)
        assertTrue("the reservation replaces the plain counter, nothing is counted twice", data.sentLog.isEmpty())
    }

    @Test fun aStallAfterTheUploadKeepsTheReservationCountedWithItsPhoto() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.costly = true; data.reserving = true
        failWith = java.net.SocketTimeoutException("read timed out") // the server took the whole photo, then went quiet
        err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        assertEquals(listOf(listOf<Any>(DataKind.PHOTO, 517L, 0L, 1)), data.settlements)
    }

    @Test fun aCallThatNeverConnectedReleasesItsReservation() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.costly = true; data.reserving = true
        failWith = java.net.UnknownHostException("no dns")
        err { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        assertEquals(listOf(listOf<Any>(DataKind.PHOTO, 0L, 0L, 0)), data.settlements)
    }

    @Test fun aCancelledUploadKeepsTheReservationCounted() = runTest {
        ep("v", caps = arrayOf(Capability.CHAT, Capability.VISION)); bindings.set(RoleBinding(Role.VISION_EXTRACT, "v", "m")).getOrThrow()
        data.costly = true; data.reserving = true
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        onVision = { started.complete(Unit); kotlinx.coroutines.delay(60_000L) }
        val job = launch { router.complete(Role.VISION_EXTRACT, CallClass.EXTRACT) { ModelRequest("q", img, liveness = AllLive, requireImages = true) } }
        started.await(); job.cancelAndJoin()
        assertEquals(listOf(listOf<Any>(DataKind.PHOTO, 517L, 0L, 1)), data.settlements)
    }

    // ---- Codex data-saver r3: P1-1 saw-costly, P1-2 LAN/free never reserve, P1-3 durable admission off the caller's thread ----

    @Test fun aLanCallAllowedForFreeTakesNoReservationAndNeverFailsOnOne() = runTest {
        ep("lan", url = "https://192.168.1.5:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        data.costly = true; data.reserving = true; data.failReserve = true; data.lanFree = true // a metered or unvalidated Wi-Fi, counters unreadable: a reservation would fail
        val r = router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }
        assertEquals("lan/m", r.text)
        assertTrue("no reservation was even asked for", data.reserveThreads.isEmpty())
        assertTrue("and nothing was counted as costly", data.costlyAtStartLog.all { !it } && data.settlements.isEmpty())
        // the same failing reservation still refuses a remote call on the costly network (fail closed for costly classes)
        ep("rem", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "rem", "m")).getOrThrow()
        assertTrue(err { router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") } } is DataSaverBlockedException)
    }

    @Test fun aLanCallOnMeteredUnvalidatedWifiWithUnreadableCountersStillGoesThrough() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        val p = app.getSharedPreferences("r3_lan", android.content.Context.MODE_PRIVATE); p.edit().clear().putString("mobile_bytes_small", "lots").commit()
        val snap = com.rokid.context.core.net.NetSnapshot(true, com.rokid.context.core.net.Transport.WIFI, validated = false, notMetered = false, notRoaming = true)
        val graph = com.rokid.context.ai.datasaver.DataSaverGraph(com.rokid.context.ai.datasaver.DataSaverStore(p), { snap }, epochDay = { 100L })
        val r = ModelRouter(bindings, endpoints, providers, callGate, graph)
        ep("lan", url = "https://192.168.1.5:11434", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "lan", "m")).getOrThrow()
        assertEquals("lan/m", r.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }.text)
        assertEquals("nothing was added to the ledger", 0L, graph.mobile.today().usage.bytes)
    }

    @Test fun aCallAdmittedOnFreeWifiThatEndsOnACostlyNetworkIsCountedAsCostly() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        onGenerate = { data.costly = true } // the connection turned out to be on a costly network
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }
        assertEquals("the bytes count against the daily cap", listOf(true), data.costlyAtStartLog)
        assertEquals(1, data.sentLog.size)
    }

    @Test fun aCallThatStaysOnFreeWifiIsStillNotCounted() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }
        assertTrue(data.costlyAtStartLog.all { !it })
    }

    @Test fun theReservationCommitDoesNotRunOnTheCallingThread() = runTest {
        ep("a", caps = arrayOf(Capability.CHAT)); bindings.set(RoleBinding(Role.ANSWER, "a", "m")).getOrThrow()
        data.costly = true; data.reserving = true
        val caller = Thread.currentThread()
        router.complete(Role.ANSWER, CallClass.USER) { ModelRequest("hello") }
        assertEquals(1, data.reserveThreads.size)
        assertTrue("the durable commit ran on an IO thread, not the caller", data.reserveThreads.none { it === caller } && data.reserveThreads[0].name.contains("DefaultDispatcher"))
    }
}
