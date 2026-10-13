package com.rokid.context.ai

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.EndpointDao
import com.rokid.context.memory.db.EndpointEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** F18: the vault is the Keystore; save, keyFor and delete reach it off the main thread (the test thread is Main). */
    @Test fun vaultCallsNeverRunOnTheMainThread() = runTest {
        val spy = MainThreadVault()
        val r = EndpointRepository(db.endpointDao(), spy)
        val saved = r.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        r.save(saved, apiKey = null).getOrThrow()
        assertEquals("k1", r.keyFor(saved))
        r.save(saved.copy(kind = EndpointKind.OLLAMA, baseUrl = "http://localhost:1"), apiKey = "").getOrThrow()
        r.delete("e1")
        r.sweepOrphanKeys()
        assertTrue(spy.onMain.size >= 6)
        assertEquals(listOf<Boolean>(), spy.onMain.filter { it })
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

    @Test fun httpWithAKeyIsRejectedEvenOnTheLanAndKeylessLocalIsAllowed() = runTest {
        fun o(url: String) = e(url).copy(kind = EndpointKind.OPENAI_COMPAT)
        assertEquals("Use https:// when sending a key", repo.save(o("http://api.example.com"), apiKey = "k").exceptionOrNull()!!.message)
        assertEquals("Use https:// when sending a key", repo.save(o("http://box.local:8080"), apiKey = "k").exceptionOrNull()!!.message)
        assertTrue(repo.all().isEmpty())
        assertTrue(repo.save(o("http://box.local:8080"), apiKey = null).isSuccess)
    }

    @Test fun keptKeyStillBlocksRemoteHttpOnResave() = runTest {
        fun o(url: String) = e(url).copy(kind = EndpointKind.OPENAI_COMPAT)
        repo.save(o("https://api.example.com"), apiKey = "k").getOrThrow()
        assertTrue(repo.save(o("http://api.example.com"), apiKey = null).isFailure)
    }

    // ---- Phase 8 D2 ----

    private class FailingVault(private val inner: KeyVault = InMemoryKeyVault(), val log: MutableList<String> = mutableListOf()) : KeyVault by inner {
        var failRemove = false
        override fun put(ref: String, key: String) { log += "vault.put"; inner.put(ref, key) }
        override fun remove(ref: String) { log += "vault.remove"; if (failRemove) throw IllegalStateException("Couldn't remove the API key"); inner.remove(ref) }
    }

    private class LoggingDao(private val d: EndpointDao, private val log: MutableList<String>) : EndpointDao by d {
        var failUpsert = false
        override suspend fun upsert(endpoint: EndpointEntity) { log += "dao.upsert"; if (failUpsert) throw IllegalStateException("disk full"); d.upsert(endpoint) }
        override suspend fun delete(id: String) { log += "dao.delete"; d.delete(id) }
    }

    @Test fun deleteNeverThrowsWhenTheVaultFails() = runTest {
        val v = FailingVault()
        val r = EndpointRepository(db.endpointDao(), v)
        r.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        v.failRemove = true
        r.delete("e1")
        assertNull(r.byId("e1"))
    }

    @Test fun deleteRemovesTheKeyBeforeTheRow() = runTest {
        val log = mutableListOf<String>()
        val r = EndpointRepository(LoggingDao(db.endpointDao(), log), FailingVault(log = log))
        r.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        log.clear()
        r.delete("e1")
        assertEquals(listOf("vault.remove", "dao.delete"), log)
    }

    @Test fun aFailedRowWriteLeavesNoNewKey() = runTest {
        val dao = LoggingDao(db.endpointDao(), mutableListOf()).apply { failUpsert = true }
        val r = EndpointRepository(dao, vault)
        assertTrue(r.save(e("https://h.example"), apiKey = "k1").isFailure)
        assertNull(vault.get(EndpointRepository.keyRef("e1")))
    }

    @Test fun aFailedRowWriteOnAnEditKeepsTheOldKey() = runTest {
        val dao = LoggingDao(db.endpointDao(), mutableListOf())
        val r = EndpointRepository(dao, vault)
        r.save(e("https://h.example"), apiKey = "k1").getOrThrow()
        dao.failUpsert = true
        assertTrue(r.save(e("https://h.example"), apiKey = "k2").isFailure)
        assertEquals("k1", vault.get(EndpointRepository.keyRef("e1")))
    }

    @Test fun orphanKeysAreSweptAtStartup() = runTest {
        repo.save(e("https://h.example").copy(id = "a"), apiKey = "ka").getOrThrow()
        vault.put(EndpointRepository.keyRef("b"), "kb")
        assertEquals(1, repo.sweepOrphanKeys())
        assertNull(vault.get(EndpointRepository.keyRef("b")))
    }

    @Test fun sweepKeepsKeysOfLiveEndpoints() = runTest {
        repo.save(e("https://h.example").copy(id = "a"), apiKey = "ka").getOrThrow()
        vault.put(EndpointRepository.keyRef("b"), "kb")
        repo.sweepOrphanKeys()
        assertEquals("ka", vault.get(EndpointRepository.keyRef("a")))
    }

    @Test fun saveRethrowsCancellation() = runTest {
        val dao = object : EndpointDao by db.endpointDao() {
            override suspend fun upsert(endpoint: EndpointEntity) = throw CancellationException("stop")
        }
        val thrown = runCatching { EndpointRepository(dao, vault).save(e("https://h.example"), apiKey = "k1") }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    // ---- Phase 8 D fix round 1 ----

    /** I1: the sweep can't see a key whose row is still being written (both hold the repository's write lock). */
    @Test fun aSaveInterleavedWithTheSweepKeepsItsKey() = kotlinx.coroutines.runBlocking {
        val upsertStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val dao = object : EndpointDao by db.endpointDao() {
            override suspend fun upsert(endpoint: EndpointEntity) { upsertStarted.complete(Unit); release.await(); db.endpointDao().upsert(endpoint) }
        }
        val r = EndpointRepository(dao, vault)
        val save = async(kotlinx.coroutines.Dispatchers.Default) { r.save(e("https://h.example"), apiKey = "k1").getOrThrow() }
        upsertStarted.await()
        val sweep = async(kotlinx.coroutines.Dispatchers.Default) { r.sweepOrphanKeys() }
        kotlinx.coroutines.delay(300) // without the lock the sweep finishes here and removes the key
        release.complete(Unit)
        save.await()
        assertEquals(0, sweep.await())
        assertEquals("k1", vault.get(EndpointRepository.keyRef("e1")))
    }

    /** A cancellation right after the key is stored still writes the row: never a key without its row. */
    @Test fun aCancelledSaveNeverLeavesAKeyWithoutItsRow() = kotlinx.coroutines.runBlocking {
        var job: kotlinx.coroutines.Job? = null
        val cancelling = object : KeyVault by vault {
            override fun put(ref: String, key: String) { vault.put(ref, key); job!!.cancel() }
        }
        val r = EndpointRepository(db.endpointDao(), cancelling)
        job = launch(kotlinx.coroutines.Dispatchers.Default, start = kotlinx.coroutines.CoroutineStart.LAZY) { r.save(e("https://h.example"), apiKey = "k1") }
        job.start(); job.join()
        val hasKey = vault.get(EndpointRepository.keyRef("e1")) != null
        val hasRow = r.byId("e1") != null
        assertEquals(hasRow, hasKey)
    }
}
