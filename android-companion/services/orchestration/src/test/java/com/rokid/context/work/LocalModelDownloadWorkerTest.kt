package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.rokid.context.ai.Http
import com.rokid.context.ai.local.LocalModel
import com.rokid.context.ai.local.ModelFile
import com.rokid.context.data.models.LocalModelState
import com.rokid.context.data.models.LocalModelStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalModelDownloadWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
    private val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
    private val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }

    private val data = ByteArray(512 * 1024) { (it * 31 % 253).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val model = LocalModel(
        id = "test-model", title = "Test", file = "test.gguf", url = "https://localhost:${server.port}/resolve/abc/test.gguf",
        sha256 = sha, bytes = data.size.toLong(), languages = "English", dims = 4, ramEstimateMb = 1, license = "MIT",
    )
    private lateinit var store: LocalModelStore

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        LocalModelStore.modelsDir(ctx).deleteRecursively()
        LocalModelDownloadWorker.resolve = { id -> model.takeIf { it.id == id } }
        LocalModelDownloadWorker.client = { Http.downloadClient().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build() }
        LocalModelDownloadWorker.freeBytes = { Long.MAX_VALUE }
        store = LocalModelStore(ctx)
    }

    @After fun tearDown() { LocalModelDownloadWorker.resetForTests(); server.shutdown() }

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<LocalModelDownloadWorker>(ctx).setInputData(workDataOf(LocalModelDownloadWorker.KEY_MODEL_ID to model.id)).build().doWork()
    }

    private fun part() = File(LocalModelStore.modelsDir(ctx), model.file + ".part")
    private fun body(from: Int = 0) = Buffer().write(data, from, data.size - from)
    private fun failure(message: String) = ListenableWorker.Result.failure(workDataOf(LocalModelDownloadWorker.KEY_MESSAGE to message))

    @Test fun fullDownloadVerifiesAndRenames() = runBlocking {
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, store.fileFor(model).readBytes())
        assertFalse(part().exists())
        assertEquals(LocalModelState.Ready, store.state(model).first())
    }

    @Test fun bundleDownloadsEveryFileAndReadyNeedsAll() = runBlocking {
        val data2 = ByteArray(1000) { (it * 7 % 251).toByte() }
        val sha2 = MessageDigest.getInstance("SHA-256").digest(data2).joinToString("") { "%02x".format(it) }
        val second = com.rokid.context.ai.local.ModelFile("second.onnx", "https://localhost:${server.port}/resolve/abc/onnx/second.onnx", sha2, data2.size.toLong())
        val bundle = model.copy(files = model.files + second)
        LocalModelDownloadWorker.resolve = { id -> bundle.takeIf { it.id == id } }
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(LocalModelState.Absent, store.state(bundle).first())
        server.enqueue(MockResponse().setBody(Buffer().write(data2)))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertArrayEquals(data, store.fileFor(model).readBytes())
        assertArrayEquals(data2, File(LocalModelStore.modelsDir(ctx), "second.onnx").readBytes())
        assertEquals(LocalModelState.Ready, store.state(bundle).first())
        File(LocalModelStore.modelsDir(ctx), "second.onnx").delete()
        assertEquals(LocalModelState.Absent, store.state(bundle).first())
    }

    @Test fun interruptedDownloadResumesWithRangeHeader() {
        server.enqueue(MockResponse().setBody(body()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertEquals(ListenableWorker.Result.retry(), runWorker())
        val have = part().length().toInt()
        assertTrue("some bytes kept: $have", have in 1 until data.size)
        assertFalse(store.fileFor(model).exists())
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $have-${data.size - 1}/${data.size}").setBody(body(have)))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("bytes=$have-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, store.fileFor(model).readBytes())
    }

    /** Review Focus 2: a new process finds only the .part on disk and continues from it. */
    @Test fun resumesFromPartAfterProcessDeath() = runBlocking {
        LocalModelStore.modelsDir(ctx).mkdirs()
        part().writeBytes(data.copyOfRange(0, 1000))
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 1000-${data.size - 1}/${data.size}").setBody(body(1000)))
        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals("bytes=1000-", server.takeRequest().getHeader("Range"))
        assertEquals(1, server.requestCount)
        assertArrayEquals(data, store.fileFor(model).readBytes())
        assertFalse(part().exists())
        assertEquals(LocalModelState.Ready, store.state(model).first())
    }

    @Test fun hashMismatchDeletesPartAndFailsWithUserMessage() {
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(data.size) { 7 })))
        assertEquals(failure("Download didn't verify \u2014 try again"), runWorker())
        assertFalse(part().exists())
        assertFalse(store.fileFor(model).exists())
    }

    @Test fun meteredNetworkBlockedByDefault() {
        val wm = WorkManager.getInstance(ctx)
        store.enqueue(model, allowMetered = false)
        assertEquals(NetworkType.UNMETERED, wm.getWorkInfosForUniqueWork("local_model_test-model").get().single().constraints.requiredNetworkType)
        val other = model.copy(id = "other")
        store.enqueue(other, allowMetered = true)
        assertEquals(NetworkType.NOT_ROAMING, wm.getWorkInfosForUniqueWork("local_model_other").get().single().constraints.requiredNetworkType)
        assertEquals(0, server.requestCount)
    }

    /** G14 (MVP section 14.1): downloads pause on low battery, on every network choice and after the mobile-data switch. */
    @Test fun downloadsPauseOnLowBattery() {
        val wm = WorkManager.getInstance(ctx)
        fun live(id: String) = wm.getWorkInfosForUniqueWork("local_model_$id").get().single { !it.state.isFinished }
        store.enqueue(model, allowMetered = false)
        assertTrue(live(model.id).constraints.requiresBatteryNotLow())
        assertEquals(NetworkType.UNMETERED, live(model.id).constraints.requiredNetworkType)
        val other = model.copy(id = "other")
        store.enqueue(other, allowMetered = true)
        assertTrue(live(other.id).constraints.requiresBatteryNotLow())
        store.setAllowMetered(model, true)
        assertEquals(NetworkType.NOT_ROAMING, live(model.id).constraints.requiredNetworkType)
        assertTrue("the replacement keeps it", live(model.id).constraints.requiresBatteryNotLow())
    }

    /** Review Focus 3: two quick taps make one download. */
    @Test fun secondDownloadRequestIsANoOp() = runBlocking {
        val wm = WorkManager.getInstance(ctx)
        store.enqueue(model, allowMetered = false)
        val first = wm.getWorkInfosForUniqueWork("local_model_test-model").get().single().id
        store.enqueue(model, allowMetered = false)
        assertEquals(first, wm.getWorkInfosForUniqueWork("local_model_test-model").get().single().id)
        assertEquals(LocalModelState.Downloading(null), store.state(model).first())
    }

    @Test fun notEnoughSpaceFailsBeforeDownloading() {
        LocalModelDownloadWorker.freeBytes = { (data.size * 1.2).toLong() - 1 }
        assertEquals(failure("Not enough space"), runWorker())
        assertEquals(0, server.requestCount)
        assertFalse(part().exists())
    }

    /** The free-space check covers the whole bundle up front: enough for the first file alone is not enough. */
    @Test fun notEnoughSpaceForTheWholeBundleFailsBeforeDownloading() {
        val bundle = model.copy(files = listOf(ModelFile(model.file, model.url, sha, model.bytes), ModelFile("second.bin", model.url.replace("test.gguf", "second.bin"), sha, model.bytes)))
        LocalModelDownloadWorker.resolve = { id -> bundle.takeIf { it.id == id } }
        LocalModelDownloadWorker.freeBytes = { (data.size * 1.5).toLong() } // fits one file (x1.2), not both
        assertEquals(failure("Not enough space"), runWorker())
        assertEquals(0, server.requestCount)
    }

    /**
     * Final review I4: Cancel, then Download again at once on a slow link. The stopped worker never writes after its
     * replacement starts, so the replacement resumes a clean .part and the file verifies.
     */
    @Test fun cancelThenImmediateRedownloadLeavesAVerifiedFile() = runBlocking {
        // A resumed download (appending), slow: 32 KB every 400 ms. The replacement's request is answered at once.
        LocalModelStore.modelsDir(ctx).mkdirs()
        part().writeBytes(data.copyOfRange(0, 1000))
        val requests = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val from = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                val response = MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes $from-${data.size - 1}/${data.size}").setBody(body(from))
                return if (requests.getAndIncrement() == 0) response.throttleBody(32 * 1024, 400, java.util.concurrent.TimeUnit.MILLISECONDS) else response
            }
        }
        val first = TestListenableWorkerBuilder<LocalModelDownloadWorker>(ctx).setInputData(workDataOf(LocalModelDownloadWorker.KEY_MODEL_ID to model.id)).build()
        val running = launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { first.doWork() } }
        while (part().length() < 1000 + 32 * 1024) kotlinx.coroutines.delay(5)
        running.cancel() // what CoroutineWorker does when its work is cancelled or replaced; its read is blocked now

        assertEquals(ListenableWorker.Result.success(), runWorker())
        kotlinx.coroutines.withTimeout(10_000) { running.join() }
        Thread.sleep(800) // a stray chunk from the stopped worker would have landed by now
        assertArrayEquals(data, store.fileFor(model).readBytes())
        assertFalse("nothing written after the rename", part().exists())
        assertTrue(com.rokid.context.ai.local.ModelFileVerifier.verify(store.fileFor(model), model.bytes, model.sha256))
    }

    /** P3-9: a local foreign SIM does not report roaming, so "I'm abroad" must keep "Allow mobile data" off for downloads. */
    @Test fun abroadKeepsAMobileDataDownloadOnWifi_andReachesAQueuedOne() {
        val wm = WorkManager.getInstance(ctx)
        fun live(id: String) = wm.getWorkInfosForUniqueWork("local_model_$id").get().single { !it.state.isFinished }
        com.rokid.context.ai.datasaver.DataSaverStore.of(ctx).setAbroad(true)
        store.enqueue(model, allowMetered = true)
        assertEquals("abroad: Wi-Fi only although mobile data is allowed", NetworkType.UNMETERED, live(model.id).constraints.requiredNetworkType)
        com.rokid.context.ai.datasaver.DataSaverStore.of(ctx).setAbroad(false)
        store.setAllowMetered(model, true)
        assertEquals("back home: the user's choice applies again", NetworkType.NOT_ROAMING, live(model.id).constraints.requiredNetworkType)
        assertEquals(0, server.requestCount)
    }

    @Test fun aQueuedDownloadDoesNothingOnAMeteredNetworkWhileAbroadIsOn() {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        org.robolectric.Shadows.shadowOf(cm).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_MOBILE, 0, true, android.net.NetworkInfo.State.CONNECTED))
        com.rokid.context.ai.datasaver.DataSaverStore.of(ctx).setAbroad(true)
        server.enqueue(MockResponse().setBody(body()))
        val wm = WorkManager.getInstance(ctx)
        store.enqueue(model, allowMetered = true) // abroad is on, so this is already UNMETERED: make it NOT_ROAMING as a download queued before the switch would be
        com.rokid.context.ai.datasaver.DataSaverStore.of(ctx).setAbroad(false); store.setAllowMetered(model, true)
        com.rokid.context.ai.datasaver.DataSaverStore.of(ctx).setAbroad(true)
        fun live() = wm.getWorkInfosForUniqueWork("local_model_test-model").get().single { !it.state.isFinished }
        assertEquals(NetworkType.NOT_ROAMING, live().constraints.requiredNetworkType)
        val before = live().id
        val r = runWorker()
        assertTrue("not a retry: no attempt is consumed and no backoff starts", r !is ListenableWorker.Result.Retry)
        assertEquals("the work was replaced by a Wi-Fi-only request", NetworkType.UNMETERED, live().constraints.requiredNetworkType)
        assertTrue(live().id != before); assertEquals("a fresh request: no attempt used", 0, live().runAttemptCount)
        assertEquals("no byte was requested over mobile data", 0, server.requestCount)
        com.rokid.context.ai.datasaver.DataSaverStore.of(ctx).setAbroad(false)
        server.enqueue(MockResponse().setBody(body()))
        assertEquals(ListenableWorker.Result.success(), runWorker())
    }

    private fun allowMobile(on: Boolean) = ctx.getSharedPreferences(LocalModelStore.APP_CONFIG, Context.MODE_PRIVATE).edit().putBoolean(LocalModelStore.KEY_ALLOW_METERED, on).commit()

    @Test fun theAbroadSwitchReconstrainsAQueuedDownloadAndRestoresItWhenOff_andARestartWithAbroadOnDoes() {
        val wm = WorkManager.getInstance(ctx)
        fun live() = wm.getWorkInfosForUniqueWork("local_model_test-model").get().single { !it.state.isFinished }
        val saver = com.rokid.context.ai.datasaver.DataSaverStore.of(ctx)
        allowMobile(true)
        store.enqueue(model, allowMetered = true)
        assertEquals(NetworkType.NOT_ROAMING, live().constraints.requiredNetworkType)
        saver.setAbroad(true); store.reevaluate(listOf(model))
        assertEquals("abroad ON: Wi-Fi only", NetworkType.UNMETERED, live().constraints.requiredNetworkType)
        saver.setAbroad(false); store.reevaluate(listOf(model))
        assertEquals("abroad OFF: the user's Allow mobile data is back", NetworkType.NOT_ROAMING, live().constraints.requiredNetworkType)
        allowMobile(false); store.reevaluate(listOf(model))
        assertEquals("the user did not allow mobile data: it stays Wi-Fi only", NetworkType.UNMETERED, live().constraints.requiredNetworkType)
        // a restart with abroad ON: the work was queued by an earlier run with NOT_ROAMING
        allowMobile(true); store.reevaluate(listOf(model)); assertEquals(NetworkType.NOT_ROAMING, live().constraints.requiredNetworkType)
        saver.setAbroad(true)
        LocalModelStore(ctx).reevaluate(listOf(model))
        assertEquals(NetworkType.UNMETERED, live().constraints.requiredNetworkType)
        assertEquals(0, server.requestCount)
    }

    /** Final review I3: the "Allow mobile data" switch reaches a download already queued, both ways; the .part stays. */
    @Test fun mobileDataSwitchReachesAQueuedDownload() {
        val wm = WorkManager.getInstance(ctx)
        LocalModelStore.modelsDir(ctx).mkdirs()
        part().writeBytes(data.copyOfRange(0, 1000))
        fun live() = wm.getWorkInfosForUniqueWork("local_model_test-model").get().single { !it.state.isFinished }
        store.enqueue(model, allowMetered = false)
        val first = live().id

        store.setAllowMetered(model, true)
        assertEquals(NetworkType.NOT_ROAMING, live().constraints.requiredNetworkType)
        assertTrue("replaced, not a second download", live().id != first)
        val second = live().id

        store.setAllowMetered(model, false)
        assertEquals(NetworkType.UNMETERED, live().constraints.requiredNetworkType)
        assertTrue(live().id != second)
        assertEquals("the .part is kept, so the new work resumes it", 1000L, part().length())

        // The same setting again leaves the work alone; with nothing queued the switch starts nothing.
        val third = live().id
        store.setAllowMetered(model, false)
        assertEquals(third, live().id)
        store.setAllowMetered(model.copy(id = "other"), true)
        assertTrue(wm.getWorkInfosForUniqueWork("local_model_other").get().isEmpty())
        assertEquals(0, server.requestCount)
    }
}
