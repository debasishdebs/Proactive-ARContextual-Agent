package com.rokid.context.phone.hub.plan

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.canvas.plan.PlanCompiler
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanPersistence
import com.rokid.context.capture.plan.PlanReceiver
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.capture.plan.ReceiverState
import com.rokid.context.core.model.CapturePlanMessage
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.Wire
import com.rokid.context.link.FakeGlassesLink
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Slice 3a Task 23a: the identity plan end to end on the phone side of the wire. The real compiler, the real [PlanSender] (over the fake link),
 * the message as the link carries it, then the REAL glasses [PlanReceiver] (the same class the glasses run, from `:core:capture`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class IdentityPlanEndToEndTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = ctx.getSharedPreferences("identity_e2e_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val link = FakeGlassesLink()
    @After fun close() { prefs.edit().clear().commit() }

    private class Mem : PlanPersistence {
        var s: ReceiverState? = null
        override fun load() = s
        override fun commit(s: ReceiverState) { this.s = s }
    }

    private val none = JsonObject(emptyMap())
    private fun node(id: String, type: String, params: JsonObject = none) = PipelineNode(id, type, id, params)
    private fun sig(id: String, name: String, domain: String) = node(id, NodeTypes.SIGNAL_IN.takeIf { name == "ctx" && domain == "RT_GLASSES" } ?: NodeTypes.SIGNAL_OUT, buildJsonObject { put("name", name); put("domain", domain) })

    /** The default capture-half graph (every node at its defaults): it compiles to the identity plan. */
    private fun identityPipeline() = Pipeline(
        2, "p", "identity",
        listOf(
            node("head", NodeTypes.IMU_HEAD_MOTION), node("thresh", NodeTypes.GATE_THRESHOLD, buildJsonObject { put("feature", "gyroPeak"); put("op", ">"); put("value", 0.6) }),
            node("settle", NodeTypes.IMU_SETTLE), node("novel", NodeTypes.GATE_NOVELTY), node("rate", NodeTypes.POLICY_CAPTURE_RATE),
            node("cap", NodeTypes.GATE_RATE_CAP), node("cam", NodeTypes.CAPTURE_IMAGE), sig("cin", "ctx", "RT_GLASSES"),
            node("hub", NodeTypes.HUB_CONTEXT), sig("cout", "ctx", "RT_PHONE"),
        ),
        listOf(
            PipelineEdge("head", "thresh"), PipelineEdge("thresh", "settle"), PipelineEdge("settle", "novel"), PipelineEdge("novel", "rate"),
            PipelineEdge("cin", "rate"), PipelineEdge("rate", "cap"), PipelineEdge("cap", "cam"), PipelineEdge("hub", "cout"),
        ),
    )

    private fun sender() = PlanSender(link, prefs, { "inst-e2e" }, { 50_000 })
    private fun wireMessages() = link.sent.filter { it.first == Channels.CAPTURE_PLAN }.map { (Wire.decode<CapturePlanMessage>(it.second) as Decoded.Ok).value }

    /** [mutate] runs on the compiled JSON before the sender sees it (the mutation check: a byte changed after the compile). */
    private fun run(mutate: (String) -> String = { it }) = runBlocking {
        val compiled = PlanCompiler.compile(identityPipeline())
        assertEquals(emptyList<String>(), compiled.problems)
        val s = sender()
        s.onSessionReady(40_000)
        s.sendShadow(mutate(compiled.planJson!!), compiled.plan!!.configHash)
        val msg = wireMessages().single()
        val rx = PlanReceiver(Mem(), { 7_000 }, { "boot-1" })
        val staged = rx.onMessage(msg)
        Triple(compiled, msg, Pair(staged, if (staged.state == PlanAckState.STAGED) rx.activated(msg.gen) else null))
    }

    @Test fun identityPlanCompilesMessagesAndBecomesEffectiveThroughTheRealReceiver() {
        val (compiled, msg, acks) = run()
        val (staged, effective) = acks
        assertEquals("the identity plan", PlanJson.configHash(PlanResolver.identity()), compiled.plan!!.configHash)
        assertEquals(PlanMode.SHADOW, msg.mode)
        assertEquals(compiled.plan!!.configHash, msg.configHash)
        assertEquals("inst-e2e", msg.phoneInstall)
        assertEquals(PlanAckState.STAGED, staged.state)
        assertNotNull(effective)
        assertEquals(PlanAckState.EFFECTIVE, effective!!.state)
        assertEquals("matching configHash", compiled.plan!!.configHash, effective.effectiveHash)
        assertEquals("matching phoneInstall", "inst-e2e", effective.phoneInstall)
        assertEquals(msg.gen, effective.gen)
        assertEquals("debug-${msg.gen}", effective.publishId)
        assertNull(effective.reason)
    }

    @Test fun theEffectiveAckClosesTheLoopOnThePhone() = runBlocking {
        val (_, msg, acks) = run()
        val s = sender()
        s.onAck(acks.second!!)
        // a sender that holds the same gen (the prefs are shared) keeps the ack as the outstanding one
        assertEquals(msg.gen, s.capturePlan.value!!.gen)
        assertEquals(PlanAckState.EFFECTIVE, s.lastAck.value!!.state)
    }

    @Test fun oneByteChangedAfterTheCompileFailsOnTheHash() {
        val (compiled, _, acks) = run { json ->
            // flip one digit of one value: the plan still parses, only its hash can tell
            val i = json.indexOfFirst { it in '1'..'8' }
            json.substring(0, i) + (json[i] + 1) + json.substring(i + 1)
        }
        val (staged, effective) = acks
        assertEquals(PlanAckState.REJECTED, staged.state)
        assertTrue("the receiver refuses on the hash or the bounds, never stages: ${staged.reason}", staged.reason in setOf("schema", "bounds"))
        assertNull(effective)
        assertNull(staged.effectiveHash)
        assertNotNull(compiled.plan)
    }
}
