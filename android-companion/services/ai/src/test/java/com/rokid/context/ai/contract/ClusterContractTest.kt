package com.rokid.context.ai.contract

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.FailureKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ModelCallException
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.prompt.PromptDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave 2 §P4.1: the cluster-batch call. One call, up to 3 clusters; ids are checked per cluster; an edited prompt is one cluster per call. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ClusterContractTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private var reply: (String) -> String = { error("no reply set") }
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply { prompts += request.prompt; return ModelReply(reply(request.prompt), 10, 2) }
            }
        })
        runBlocking {
            ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT, Capability.VISION, Capability.JSON_SCHEMA), 1), apiKey = "k").getOrThrow()
            ai.bindings.set(RoleBinding(Role.VISION_EXTRACT, "az", "gpt")).getOrThrow()
        }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun photo(c: String, f: String) = ClusterPhoto(c, f, byteArrayOf(1, 2, 3), "cluster_id: $c frame_id: $f time: 10:00 motion: STILL")
    private fun fact(vararg ids: String) =
        """{"text":"Red mug","type":"object","confidence":0.9,"tags":["kitchen"],"source_frame_ids":[${ids.joinToString(",") { "\"$it\"" }}]}"""
    private fun cl(c: String, vararg ids: String) = """{"cluster_id":"$c","summary":"Scene $c","facts":[${fact(*ids)}]}"""
    private fun cap(f: String) = """{"frame_id":"$f","caption":"Photo $f"}"""

    private val sent = mapOf("c1" to listOf("a", "b"), "c2" to listOf("d"))
    private fun out(clusters: List<ClusterResult>, captions: List<String>) =
        ClusterOutput(clusters, captions.map { FrameCaption(it, "Photo $it") })
    private fun res(c: String, vararg ids: String) =
        ClusterResult(c, "Scene $c", listOf(Fact("Red mug", "object", 0.9, listOf("kitchen"), ids.toList())))

    @Test fun validRoundTrip() = runBlocking<Unit> {
        reply = { """{"clusters":[${cl("c1", "a", "b")},${cl("c2", "d")}],"captions":[${cap("a")},${cap("b")},${cap("d")}]}""" }
        val photos = listOf(photo("c1", "a"), photo("c1", "b"), photo("c2", "d"))
        val o = ClusterContract.call(ai.runner, photos, CallClass.EXTRACT)
        assertEquals(listOf("c1", "c2"), o.clusters.map { it.clusterId })
        assertEquals(listOf("a", "b"), o.clusters[0].facts.single().sourceFrameIds)
        assertEquals(listOf("a", "b", "d"), o.captions.map { it.frameId })
        assertEquals(1, prompts.size)
        assertTrue(ClusterContract.validate(o, sent))
        assertTrue(ClusterContract.promptIsCurrent(DefaultSchemas.text(PromptKey.CLUSTER_SUMMARY)))
        assertEquals(PromptKey.VISION_EXTRACT, PromptKey.of(Role.VISION_EXTRACT))
    }

    @Test fun rejectsUnknownCluster() {
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "a"), res("c2", "d"), res("zzz", "a")), listOf("a", "b", "d")), sent))
        // A sent cluster missing, or with a blank summary.
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "a")), listOf("a", "b", "d")), sent))
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "a").copy(summary = "  "), res("c2", "d")), listOf("a", "b", "d")), sent))
        assertTrue(ClusterContract.validate(out(listOf(res("c1", "a"), res("c2", "d")), listOf("a", "b", "d")), sent))
    }

    @Test fun rejectsCrossClusterSourceId() {
        // "d" was sent, but for c2, not c1.
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "a", "d"), res("c2", "d")), listOf("a", "b", "d")), sent))
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "nope"), res("c2", "d")), listOf("a", "b", "d")), sent))
    }

    @Test fun rejectsMissingCaption() {
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "a"), res("c2", "d")), listOf("a", "d")), sent))
        assertFalse(ClusterContract.validate(out(listOf(res("c1", "a"), res("c2", "d")), listOf("a", "b", "d", "x")), sent))
    }

    @Test fun invalidBatchRetriesEachClusterAlone() = runBlocking<Unit> {
        // The batch cites the wrong cluster's frame (valid JSON, so no runner retry); each cluster alone is fine.
        reply = { p ->
            when {
                "frame_id: d" in p && "frame_id: a" in p -> """{"clusters":[${cl("c1", "d")},${cl("c2", "d")}],"captions":[${cap("a")},${cap("b")},${cap("d")}]}"""
                "frame_id: a" in p -> """{"clusters":[${cl("c1", "a")}],"captions":[${cap("a")},${cap("b")}]}"""
                else -> """{"clusters":[${cl("c2", "d")}],"captions":[${cap("d")}]}"""
            }
        }
        val o = ClusterContract.call(ai.runner, listOf(photo("c1", "a"), photo("c1", "b"), photo("c2", "d")), CallClass.EXTRACT)
        assertEquals(listOf("c1", "c2"), o.clusters.map { it.clusterId })
        assertEquals(3, prompts.size) // the batch, then each cluster alone
        assertTrue(ClusterContract.validate(o, sent))
    }

    @Test fun clusterStillInvalidAloneIsLeftOutAndAllInvalidThrows() = runBlocking<Unit> {
        reply = { p ->
            if ("frame_id: a" in p) """{"clusters":[${cl("c1", "d")}],"captions":[${cap("a")},${cap("b")}]}"""
            else """{"clusters":[${cl("c2", "d")}],"captions":[${cap("d")}]}"""
        }
        val o = ClusterContract.call(ai.runner, listOf(photo("c1", "a"), photo("c1", "b"), photo("c2", "d")), CallClass.EXTRACT)
        assertEquals(listOf("c2"), o.clusters.map { it.clusterId }) // c1 is the caller's to fail
        reply = { """{"clusters":[${cl("c1", "d")}],"captions":[${cap("a")}]}""" }
        val e = runCatching { ClusterContract.call(ai.runner, listOf(photo("c1", "a")), CallClass.EXTRACT) }.exceptionOrNull() as ModelCallException
        assertEquals(FailureKind.INVALID_OUTPUT, e.kind)
    }

    @Test fun editedPromptIsOneClusterPerCall() = runBlocking<Unit> {
        // A stored prompt whose schema has no cluster_id / source_frame_ids: not current.
        val old = """{"type":"object","properties":{"clusters":{"type":"array","maxItems":3,"items":{"type":"object","properties":{
            "summary":{"type":"string","minLength":1},"facts":{"type":"array","items":{"type":"object","properties":{
            "text":{"type":"string","minLength":1},"type":{"type":"string"},"confidence":{"type":"number"},"tags":{"type":"array","items":{"type":"string"}}},
            "required":["text","type","confidence","tags"]}}},"required":["summary","facts"]}}},"required":["clusters"]}"""
        assertFalse(ClusterContract.promptIsCurrent(old))
        assertFalse(ClusterContract.promptIsCurrent(null))
        reply = { """{"clusters":[{"summary":"A scene","facts":[{"text":"Red mug","type":"object","confidence":0.9,"tags":[]}]}]}""" }
        val o = ClusterContract.call(ai.runner, listOf(photo("c1", "a"), photo("c1", "b"), photo("c2", "d")), CallClass.EXTRACT, PromptDraft("T {{recent_context}}", old))
        assertEquals(2, prompts.size) // one call per cluster
        assertEquals(listOf("c1", "c2"), o.clusters.map { it.clusterId })
        // Facts without source ids are attributed to that cluster's representatives; captions are optional.
        assertEquals(listOf("a", "b"), o.clusters[0].facts.single().sourceFrameIds)
        assertEquals(listOf("d"), o.clusters[1].facts.single().sourceFrameIds)
        assertTrue(o.captions.isEmpty())
        assertNotNull(o)
    }
}
