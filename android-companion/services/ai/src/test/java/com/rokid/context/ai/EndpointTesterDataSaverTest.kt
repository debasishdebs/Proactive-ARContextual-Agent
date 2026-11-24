package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.EndpointReach
import com.rokid.context.core.net.Skipped
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EndpointEntity
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex data-saver r1 P2-8: Endpoint Test is SMALL traffic: admitted by the data saver and counted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointTesterDataSaverTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()
    private val vault = InMemoryKeyVault()
    private val repo = EndpointRepository(db.endpointDao(), vault)
    private var providerCalls = 0
    private val providers = ProviderBuilder { _, _, _ ->
        object : ModelProvider {
            override suspend fun generate(prompt: String): String { providerCalls++; return "OK" }
            override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String = generate(prompt)
        }
    }

    private class Gate(var allowSmall: Boolean = true) : DataGate {
        val asked = mutableListOf<Pair<DataKind, EndpointReach>>()
        val skippedLog = mutableListOf<Pair<DataKind, String>>()
        val sentLog = mutableListOf<List<Any>>()
        override fun decide(kind: DataKind, reach: EndpointReach): DataDecision {
            asked += kind to reach
            return if (reach == EndpointReach.DEVICE || allowSmall) DataDecision.ALLOW else DataDecision(false, "roaming")
        }
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) { skippedLog += kind to d.reason }
        override fun costlyNow() = true
        override fun sentAt(kind: DataKind, txBytes: Long, rxBytes: Long, photos: Int, costlyAtStart: Boolean) { sentLog += listOf(kind, txBytes, rxBytes, photos, costlyAtStart) }
    }

    private suspend fun save(url: String) {
        db.endpointDao().upsert(EndpointEntity("e", "t", EndpointKind.OLLAMA.name, url, "2024-10-21", "m", EndpointRepository.keyRef("e"), Capability.CHAT.name, 1))
    }

    @Test fun aTestIsRefusedOnRoamingWhenSmallRequestsAreOffAndSendsNothing() = runTest {
        save("https://api.example.com")
        val gate = Gate(allowSmall = false)
        val r = EndpointTester(repo, providers, dataGate = gate).test("e", "m")
        assertFalse(r.ok)
        assertEquals(EndpointTester.DATA_SAVER_MESSAGE, r.message)
        assertEquals("no provider call was made", 0, providerCalls)
        assertEquals(listOf(DataKind.SMALL to EndpointReach.REMOTE), gate.asked)
        assertEquals(listOf(DataKind.SMALL to "roaming"), gate.skippedLog)
        assertTrue(gate.sentLog.isEmpty())
    }

    @Test fun anAllowedTestIsCountedAsSmallTraffic() = runTest {
        save("https://api.example.com")
        val gate = Gate()
        val r = EndpointTester(repo, providers, dataGate = gate).test("e", "m")
        assertTrue(r.message, r.ok)
        assertEquals(1, providerCalls)
        assertEquals(1, gate.sentLog.size)
        assertEquals(DataKind.SMALL, gate.sentLog[0][0]); assertTrue((gate.sentLog[0][1] as Long) > 512); assertTrue((gate.sentLog[0][2] as Long) >= 512); assertEquals(true, gate.sentLog[0][4])
    }

    @Test fun aLoopbackEndpointTestIsNotCounted() = runTest {
        save("https://127.0.0.1:11434")
        val gate = Gate()
        assertTrue(EndpointTester(repo, providers, dataGate = gate).test("e", "m").ok)
        assertTrue(gate.sentLog.isEmpty())
    }
}
