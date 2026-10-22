package com.rokid.context.canvas.plan

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.PlanAckState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.memory.db.CanvasGroupStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Slice 3a Task 26: the publish state machine over a persisted row per group (S25, S62). Pure JVM: a map for the table, a fake transport, a clock by hand. */
class PlanPublishGroupsTest {
    private class MemStore : GroupStore {
        val rows = LinkedHashMap<String, CanvasGroupStateEntity>()
        override suspend fun get(groupId: String) = rows[groupId]
        override suspend fun all() = rows.values.toList()
        override suspend fun put(s: CanvasGroupStateEntity) { rows[s.groupId] = s }
    }

    private class FakeTransport : PlanTransport {
        var gen = 0L
        var failNext = false
        val sent = ArrayList<Sent>()
        data class Sent(val gen: Long, val mode: PlanMode, val planJson: String?, val hash: String?, val publishId: String)
        override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): SendOutcome {
            if (failNext) { failNext = false; throw java.io.IOException("process died") }
            gen++; sent += Sent(gen, mode, planJson, configHash, publishId)
            return SendOutcome(gen, true)
        }
    }

    private val store = MemStore()
    private val tx = FakeTransport()
    private var now = 1_000_000L
    private val activated = ArrayList<Pair<String, String?>>()
    private var activateFails = false
    private val plans = object : PlanSource {
        val m = HashMap<Pair<String, Long>, String>()
        override suspend fun save(groupId: String, publishId: Long, planJson: String) { m[groupId to publishId] = planJson }
        override suspend fun load(groupId: String, publishId: Long) = m[groupId to publishId]
    }

    private fun groups() = PlanPublishGroups(store, tx, { "inst" }, { now }, { gid, json -> if (activateFails) throw IllegalStateException("crash") else activated += gid to json }, plans)

    private fun ack(gen: Long, state: PlanAckState, hash: String? = "h1", publishId: String? = null, mode: PlanMode = PlanMode.SHADOW, install: String = "inst", reason: String? = null) =
        CapturePlanAck(gen = gen, publishId = publishId ?: "$gen".let { (store.rows["g"]?.publishId ?: 0).toString() }, mode = mode, effectiveHash = hash, state = state, reason = reason, phoneInstall = install)

    private fun publish(g: PlanPublishGroups, groupId: String = "g", publishId: Long = 7, hash: String = "h1", json: String = "{\"p\":1}", mode: PlanMode = PlanMode.SHADOW) = runBlocking {
        g.publish(groupId, publishId, mode, json, hash, trialMaxMs = null)
    }
    private fun state(groupId: String = "g") = store.rows[groupId]?.state

    @Test fun happyPathReachesEffectiveAndActivatesThePhoneHalves() = runBlocking {
        val g = groups(); publish(g)
        assertEquals("SENT", state()); assertEquals(1L, store.rows["g"]!!.gen); assertEquals("inst", store.rows["g"]!!.phoneInstall); assertEquals(now, store.rows["g"]!!.sentAtMs)
        g.onAck(ack(1, PlanAckState.STAGED)); assertEquals("SENT", state())
        g.onAck(ack(1, PlanAckState.EFFECTIVE))
        assertEquals("EFFECTIVE", state()); assertEquals(listOf("g" to "{\"p\":1}"), activated)
        g.onAck(ack(1, PlanAckState.EFFECTIVE)); assertEquals("a duplicate EFFECTIVE does not activate twice", 1, activated.size)
    }

    @Test fun matchingNeedsGenPublishIdModeHashAndInstall() = runBlocking {
        val g = groups(); publish(g)
        for (bad in listOf(ack(2, PlanAckState.EFFECTIVE), ack(1, PlanAckState.EFFECTIVE, publishId = "8"), ack(1, PlanAckState.EFFECTIVE, mode = PlanMode.TRIAL),
            ack(1, PlanAckState.EFFECTIVE, hash = "other"), ack(1, PlanAckState.EFFECTIVE, install = "elsewhere"))) {
            g.onAck(bad); assertEquals("SENT", state()); assertTrue(activated.isEmpty())
        }
        assertEquals(5, g.ignoredAcks)
    }

    @Test fun lostAckBecomesUnknownRemote() = runBlocking {
        val g = groups(); publish(g)
        now += 9 * 60_000; g.tick(); assertEquals("SENT", state())
        now += 60_000; g.tick(); assertEquals("UNKNOWN_REMOTE", state())
    }

    @Test fun lateEffectiveAfterTimeoutActivates() = runBlocking {
        val g = groups(); publish(g)
        now += 11 * 60_000; g.tick()
        g.onAck(ack(1, PlanAckState.EFFECTIVE))
        assertEquals("EFFECTIVE", state()); assertEquals(1, activated.size)
    }

    @Test fun rejectedFailsButPersistIsRetryable() = runBlocking {
        val g = groups(); publish(g)
        g.onAck(ack(1, PlanAckState.REJECTED, hash = null, reason = "persist"))
        assertEquals("a persist failure is not a verdict", "SENT", state()); assertEquals(1, g.persistRetries)
        g.onAck(ack(1, PlanAckState.EFFECTIVE)); assertEquals("EFFECTIVE", state())
        val g2 = groups(); publish(g2, "g2", 9)
        g2.onAck(ack(2, PlanAckState.REJECTED, hash = null, publishId = "9", reason = "bounds")); assertEquals("FAILED", state("g2"))
    }

    @Test fun staleEffectiveAfterRevertIgnored() = runBlocking {
        val g = groups(); publish(g)
        g.onAck(ack(1, PlanAckState.EFFECTIVE)); activated.clear()
        g.onAck(ack(1, PlanAckState.REVERTED, hash = null, reason = "auto:ceiling"))
        assertEquals("REVERTED", state())
        g.onAck(ack(1, PlanAckState.EFFECTIVE)); g.onAck(ack(1, PlanAckState.STAGED))
        assertEquals("terminal", "REVERTED", state()); assertTrue(activated.isEmpty()); assertTrue(g.ignoredAcks >= 2)
        // terminal per gen: a later publish of the group is a new gen and proceeds
        publish(g, publishId = 8); assertEquals("SENT", state()); assertEquals(2L, store.rows["g"]!!.gen)
    }

    @Test fun supersededPublishIgnoresOldAck() = runBlocking {
        val g = groups(); publish(g); publish(g, publishId = 8, hash = "h2")
        assertEquals(2L, store.rows["g"]!!.gen)
        g.onAck(ack(1, PlanAckState.EFFECTIVE, publishId = "7")); assertEquals("SENT", state()); assertTrue(activated.isEmpty()); assertEquals(1, g.ignoredAcks)
        g.onAck(ack(2, PlanAckState.EFFECTIVE, hash = "h2", publishId = "8")); assertEquals("EFFECTIVE", state())
    }

    @Test fun crashAfterPersistBeforeSend() = runBlocking {
        val g = groups(); tx.failNext = true
        try { publish(g); fail() } catch (e: java.io.IOException) { }
        assertEquals("PREPARED", state()); assertTrue(tx.sent.isEmpty())
        val restarted = groups(); restarted.recover()
        assertEquals("SENT", state()); assertEquals(1, tx.sent.size); assertEquals("{\"p\":1}", tx.sent[0].planJson); assertEquals("h1", tx.sent[0].hash); assertEquals("7", tx.sent[0].publishId)
    }

    @Test fun anInterruptedDesiredIsFailedOnRecovery() = runBlocking {
        store.rows["g"] = CanvasGroupStateEntity("g", 0, 7, "h1", "capture_plan", "DESIRED", now, "SHADOW", "inst")
        groups().recover(); assertEquals("FAILED", state()); assertTrue(tx.sent.isEmpty())
    }

    @Test fun crashAfterSendBeforeAck() = runBlocking {
        val g = groups(); publish(g)
        now += 3 * 60_000
        val restarted = groups(); restarted.recover()
        assertEquals("still waiting for the ack", "SENT", state()); assertEquals(1, tx.sent.size)
        restarted.onAck(ack(1, PlanAckState.EFFECTIVE)); assertEquals("EFFECTIVE", state())
        // and a restart after the 10 minutes finds it overdue
        val g2 = groups(); publish(g2, "g2", 9); now += 11 * 60_000
        groups().recover(); assertEquals("UNKNOWN_REMOTE", state("g2"))
    }

    @Test fun crashAfterAckBeforeActivate() = runBlocking {
        val g = groups(); publish(g)
        activateFails = true
        try { g.onAck(ack(1, PlanAckState.EFFECTIVE)); fail() } catch (e: IllegalStateException) { }
        assertEquals("the ack is durable before the phone halves", "EFFECTIVE", state()); assertTrue(activated.isEmpty())
        activateFails = false
        groups().recover()
        assertEquals(listOf("g" to "{\"p\":1}"), activated)
    }

    @Test fun compensationUsesHigherGen() = runBlocking {
        val g = groups(); publish(g); now += 11 * 60_000; g.tick()
        assertEquals("UNKNOWN_REMOTE", state())
        g.compensate("g", PreviousDesired(PlanMode.SETTINGS, null, null))
        assertEquals(2L, tx.sent.last().gen); assertEquals(PlanMode.SETTINGS, tx.sent.last().mode)
        assertEquals(2L, store.rows["g"]!!.gen); assertEquals("SENT", state()); assertTrue(store.rows["g"]!!.publishId != 7L)
        // the old gen answering late is superseded
        g.onAck(ack(1, PlanAckState.EFFECTIVE, publishId = "7")); assertEquals("SENT", state()); assertTrue(activated.isEmpty())
        g.onAck(CapturePlanAck(gen = 2, publishId = store.rows["g"]!!.publishId.toString(), mode = PlanMode.SETTINGS, effectiveHash = null, state = PlanAckState.EFFECTIVE, phoneInstall = "inst"))
        assertEquals("EFFECTIVE", state()); assertEquals("g" to null, activated.single())
    }

    @Test fun compensationOnlyFromUnknownRemote() = runBlocking {
        val g = groups(); publish(g)
        assertEquals(false, g.compensate("g", PreviousDesired(PlanMode.SETTINGS, null, null))); assertEquals(1, tx.sent.size)
    }

    @Test fun aNewPublishOverAnUnknownRemoteCompensatesFirst() = runBlocking {
        val g = groups(); publish(g); now += 11 * 60_000; g.tick()
        g.publish("g", 8, PlanMode.SHADOW, "{\"p\":2}", "h2", null, previous = PreviousDesired(PlanMode.SETTINGS, null, null))
        assertEquals(listOf(PlanMode.SHADOW, PlanMode.SETTINGS, PlanMode.SHADOW), tx.sent.map { it.mode }); assertEquals(3L, store.rows["g"]!!.gen)
        assertNotNull(store.rows["g"])
    }

    /** A transport with the real split: [stage] allocates and persists the gen, [transmit] puts it on the link (a hook lets a test act at that instant). */
    private inner class SplitTransport : PlanTransport {
        var gen = 0L
        val staged = ArrayList<Triple<Long, PlanMode, String>>()
        val transmitted = ArrayList<Long>()
        val discarded = ArrayList<String>()
        var failStage = false
        var onTransmit: suspend (Long) -> Unit = {}
        override suspend fun send(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): SendOutcome =
            SendOutcome(stage(mode, planJson, configHash, publishId, trialMaxMs), transmit(gen))
        override suspend fun stage(mode: PlanMode, planJson: String?, configHash: String?, publishId: String, trialMaxMs: Long?): Long {
            gen++; staged += Triple(gen, mode, publishId)
            if (failStage) throw java.io.IOException("process died after the gen was persisted")
            return gen
        }
        override suspend fun transmit(gen: Long): Boolean { transmitted += gen; onTransmit(gen); return true }
        override suspend fun discard(publishId: String) { discarded += publishId }
    }

    @Test fun theGenIsStoredWithSentBeforeTransmittingSoAFastEffectiveAckIsCorrelated() = runBlocking {
        val sp = SplitTransport()
        val g = PlanPublishGroups(store, sp, { "inst" }, { now }, { gid, json -> activated += gid to json }, plans)
        sp.onTransmit = { gen -> g.onAck(ack(gen, PlanAckState.EFFECTIVE, publishId = "7")) }   // the glasses answer before transmit returns
        g.publish("g", 7, PlanMode.SHADOW, "{\"p\":1}", "h1", null)
        assertEquals("EFFECTIVE", state()); assertEquals(listOf("g" to "{\"p\":1}"), activated); assertEquals(0, g.ignoredAcks)
    }

    @Test fun aCrashAfterStagingLeavesATrialPreparedAndRecoveryDiscardsTheStagedDesiredState() = runBlocking {
        val sp = SplitTransport(); sp.failStage = true
        val g = PlanPublishGroups(store, sp, { "inst" }, { now }, { _, _ -> }, plans)
        try { g.publish("g", 7, PlanMode.TRIAL, "{\"p\":1}", "h1", 3_600_000L); fail() } catch (e: java.io.IOException) { }
        assertEquals("PREPARED", state()); assertTrue("nothing was on the link", sp.transmitted.isEmpty())
        PlanPublishGroups(store, sp, { "inst" }, { now }, { _, _ -> }, plans).recover()
        assertEquals("FAILED", state()); assertEquals("the sender must not resend what the phone failed", listOf("7"), sp.discarded)
    }

    @Test fun aCrashAfterStagingOfANonTrialIsRestagedAndSent() = runBlocking {
        val sp = SplitTransport(); sp.failStage = true
        val g = PlanPublishGroups(store, sp, { "inst" }, { now }, { _, _ -> }, plans)
        try { g.publish("g", 7, PlanMode.SHADOW, "{\"p\":1}", "h1", null); fail() } catch (e: java.io.IOException) { }
        sp.failStage = false
        PlanPublishGroups(store, sp, { "inst" }, { now }, { _, _ -> }, plans).recover()
        assertEquals("SENT", state()); assertEquals(sp.gen, store.rows["g"]!!.gen); assertEquals(listOf(sp.gen), sp.transmitted); assertTrue(sp.discarded.isEmpty())
    }

    @Test fun anAckOfAGroupThatAnotherGroupSupersededNeverActivatesItsHalves() = runBlocking {
        val g = groups(); publish(g, "gA", 7, "hA"); publish(g, "gB", 8, "hB")
        assertEquals(2L, store.rows["gB"]!!.gen)
        g.onAck(CapturePlanAck(gen = 1, publishId = "7", mode = PlanMode.SHADOW, effectiveHash = "hA", state = PlanAckState.EFFECTIVE, phoneInstall = "inst"))
        assertEquals("SENT", state("gA")); assertTrue("A's ASYNC half must stay off", activated.isEmpty()); assertEquals(1, g.ignoredAcks)
        g.onAck(CapturePlanAck(gen = 2, publishId = "8", mode = PlanMode.SHADOW, effectiveHash = "hB", state = PlanAckState.EFFECTIVE, phoneInstall = "inst"))
        assertEquals(listOf("gB" to "{\"p\":1}"), activated)
    }
}
