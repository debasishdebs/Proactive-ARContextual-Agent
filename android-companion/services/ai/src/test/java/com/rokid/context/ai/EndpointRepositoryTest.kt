package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EndpointRepositoryTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val vault = InMemoryKeyVault()
    private val repo = EndpointRepository(db.endpointDao(), vault)
    @After fun close() = db.close()

    private fun e(url: String) = Endpoint("e1", "Azure", EndpointKind.AZURE_OPENAI, url, "2024-10-21", listOf("gpt-5.4"), setOf(Capability.CHAT, Capability.VISION), 1)

    @Test fun savesNormalisedUrlAndKeepsKeyOutOfRoom() = runTest {
        val saved = repo.save(e(" my-res.openai.azure.com/ "), apiKey = " secret ").getOrThrow()
        assertEquals("https://my-res.openai.azure.com", saved.baseUrl)
        assertEquals("secret", repo.keyFor(saved))
        assertFalse(db.endpointDao().byId("e1").toString().contains("secret"))
        assertEquals(setOf(Capability.CHAT, Capability.VISION), repo.byId("e1")!!.capabilities)
    }

    @Test fun rejectsBrokenInputWithoutStoringAnything() = runTest {
        assertTrue(repo.save(e("https://bad host"), apiKey = "k").isFailure)
        assertTrue(repo.all().isEmpty())
    }

    @Test fun nullKeyKeepsExistingKey() = runTest {
        repo.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        repo.save(e("https://h.example").copy(name = "Renamed"), apiKey = null).getOrThrow()
        assertEquals("k1", repo.keyFor(repo.byId("e1")!!))
        assertEquals("Renamed", repo.byId("e1")!!.name)
    }

    @Test fun deleteRemovesKey() = runTest {
        val saved = repo.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        repo.delete("e1")
        assertEquals(null, repo.byId("e1")); assertEquals(null, vault.get(EndpointRepository.keyRef(saved.id)))
    }

    @Test fun keyWithInnerSpaceIsRejectedAndNothingStored() = runTest {
        val r = repo.save(e("https://h.example"), apiKey = "ab cd")
        assertTrue(r.isFailure)
        assertEquals("Key can't contain spaces", r.exceptionOrNull()!!.message)
        assertTrue(repo.all().isEmpty())
        assertEquals(null, vault.get(EndpointRepository.keyRef("e1")))
    }

    @Test fun nonAsciiKeyIsRejectedAndNothingStored() = runTest {
        val r = repo.save(e("https://h.example"), apiKey = "abc\u200B123")
        assertEquals("Key has invalid characters", r.exceptionOrNull()!!.message)
        assertTrue(repo.all().isEmpty())
        assertEquals(null, vault.get(EndpointRepository.keyRef("e1")))
    }

    @Test fun azureHttpIsRejectedAndNothingStored() = runTest {
        assertTrue(repo.save(e("http://h.example"), apiKey = "k").isFailure)
        assertTrue(repo.all().isEmpty())
    }

    @Test fun openAiCompatRemoteHttpWithKeyRejectedButLanAllowed() = runTest {
        fun o(url: String) = e(url).copy(kind = EndpointKind.OPENAI_COMPAT)
        val bad = repo.save(o("http://api.example.com"), apiKey = "k")
        assertEquals("Use https:// when sending a key", bad.exceptionOrNull()!!.message)
        assertTrue(repo.all().isEmpty())
        assertTrue(repo.save(o("http://192.168.1.5:8080"), apiKey = "k").isSuccess)
    }

    @Test fun keptKeyStillBlocksRemoteHttpOnResave() = runTest {
        fun o(url: String) = e(url).copy(kind = EndpointKind.OPENAI_COMPAT)
        repo.save(o("https://api.example.com"), apiKey = "k").getOrThrow()
        assertTrue(repo.save(o("http://api.example.com"), apiKey = null).isFailure)
    }
}
