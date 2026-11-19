package com.rokid.context.work.frames

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The photo reach is read off the main thread: a cold call on main answers REMOTE (fail closed) at once and warms the cache in the background. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class KeyframeGraphReachTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun aColdCallOnTheMainThreadNeverBlocksAndTheCacheWarmsInTheBackground() {
        runBlocking {
            val ai = AiGraph.get(ctx)
            ai.endpoints.save(Endpoint("lan", "Box", EndpointKind.OLLAMA, "http://mybox.local:11434", null, listOf("m"), setOf(Capability.CHAT, Capability.VISION), 1), apiKey = null).getOrThrow()
            ai.bindings.set(RoleBinding(Role.VISION_EXTRACT, "lan", "m")).getOrThrow()
        }
        val graph = KeyframeGraph.get(ctx)
        assertEquals("main thread, cold cache: REMOTE at once, no database read", EndpointReach.REMOTE, graph.photoReach())
        var reach = graph.photoReach()
        val end = System.currentTimeMillis() + 5_000
        while (reach != EndpointReach.LAN && System.currentTimeMillis() < end) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper(); reach = graph.photoReach() }
        assertEquals("the background warm-up filled the cache", EndpointReach.LAN, reach)
    }
}
