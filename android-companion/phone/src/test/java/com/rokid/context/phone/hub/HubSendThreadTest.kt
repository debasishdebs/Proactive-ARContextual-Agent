package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Health
import com.rokid.context.link.FakeGlassesLink
import com.rokid.context.link.FrameAdmission
import com.rokid.context.link.FrameReassembler
import com.rokid.context.link.GlassesLink
import com.rokid.context.link.ReceivedFrameLedger
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Final review I3: CxrGlassesLink is touched only on its scope (Main in the app). A pause or resume called from an IO
 * thread (PausePipelineControl) must still send pc_settings on the hub's scope.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubSendThreadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private val db = testDb(ctx)
    private val main = Executors.newSingleThreadExecutor { Thread(it, "hub-main") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    @After fun close() { scope.cancel(); main.close(); db.close() }

    private val fake = FakeGlassesLink()
    private val sendThreads = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
    private val link = object : GlassesLink by fake {
        override suspend fun send(channel: String, json: String): Boolean {
            sendThreads += channel to Thread.currentThread().name
            return fake.send(channel, json)
        }
    }

    private fun hub(): Hub {
        val pauses = HubPauses(db.pauseDao())
        val ledger = ReceivedFrameLedger(File(tmp.root, "ledger.txt"))
        val ingestor = FrameIngestor(File(tmp.root, "inbox"), FrameReassembler(), FrameAdmission(ledger, pauses), ledger, onStored = {})
        val settings = HubSettingsStore(ctx.getSharedPreferences("hub_thread_${tmp.root.name}", 0)) { "hud" }
        val sources = object : HubSources {
            override suspend fun today() = emptyList<com.rokid.context.core.model.MemoryLine>()
            override suspend fun queueDepth() = 0
            override suspend fun aiHealth(nowMs: Long): Health = Health.Ok
        }
        return Hub(scope, link, pauses, settings, ingestor, sources, io = Dispatchers.IO)
    }

    @Test fun pauseAndResumeFromAnIoThreadSendSettingsOnTheHubScope() = runBlocking {
        val h = hub()
        val r = withContext(Dispatchers.IO) { h.pausePhone(null) }
        withContext(Dispatchers.IO) { h.resume(setOf(r.pauseId)) }
        val settings = sendThreads.filter { it.first == Channels.SETTINGS }
        assertEquals(2, settings.size)
        assertTrue("sent on ${settings.map { it.second }}", settings.all { it.second.startsWith("hub-main") })
    }
}
