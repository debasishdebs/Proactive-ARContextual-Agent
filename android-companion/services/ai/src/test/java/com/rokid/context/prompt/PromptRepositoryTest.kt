package com.rokid.context.prompt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PromptRepositoryTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val settings = InMemoryPromptSettings()
    private var clock = 1_000L
    private val repo = PromptRepository(db.promptDao(), BuiltInPromptSeeds, settings) { clock++ }
    @After fun close() = db.close()

    private fun draft(t: String) = PromptDraft("Q: {{question}} M: {{retrieved_memories}} $t", DefaultSchemas.text(PromptKey.ANSWER))

    @Test fun seedsEveryKeyOnceWithItsDefaultSchema() = runBlocking {
        repo.ensureSeeded(); repo.ensureSeeded()
        PromptKey.entries.forEach { k ->
            val v = repo.active(k)
            assertEquals(1, v.version); assertEquals(DefaultSchemas.text(k), v.outputSchema)
            assertEquals(1, repo.history(k).first().size)
        }
    }

    @Test fun versioningOnAppendsAndActivates() = runBlocking {
        assertEquals(2, repo.save(PromptKey.ANSWER, draft("a"), "first edit", testedOk = true))
        val active = repo.active(PromptKey.ANSWER)
        assertEquals(2, active.version); assertTrue(active.template.endsWith("a")); assertTrue(active.testedOk)
        assertEquals(listOf(2, 1), repo.history(PromptKey.ANSWER).first().map { it.version })
    }

    @Test fun versioningOffOverwritesTheActiveVersionInPlace() = runBlocking {
        repo.save(PromptKey.ANSWER, draft("a"), "", true)
        settings.setKeepVersions(false)
        assertEquals(2, repo.save(PromptKey.ANSWER, draft("b"), "", true))
        assertEquals(listOf(2, 1), repo.history(PromptKey.ANSWER).first().map { it.version })
        assertTrue(repo.active(PromptKey.ANSWER).template.endsWith("b"))
        assertTrue(repo.version(PromptKey.ANSWER, 1)!!.template.contains("{{question}}")) // history untouched
    }

    @Test fun restoreCopiesIntoANewVersionOrOverwritesWhenOff() = runBlocking {
        repo.save(PromptKey.ANSWER, draft("a"), "", true)
        val seedText = repo.version(PromptKey.ANSWER, 1)!!.template
        assertEquals(3, repo.restore(PromptKey.ANSWER, 1))
        assertEquals(seedText, repo.active(PromptKey.ANSWER).template)
        assertEquals("Restored from v1", repo.active(PromptKey.ANSWER).note)
        settings.setKeepVersions(false)
        assertEquals(3, repo.restore(PromptKey.ANSWER, 2))
        assertTrue(repo.active(PromptKey.ANSWER).template.endsWith("a"))
        assertEquals(3, repo.history(PromptKey.ANSWER).first().size)
    }

    @Test fun exactlyOneActiveVersionUnderConcurrentSaves() = runBlocking {
        repo.ensureSeeded()
        val versions = (1..10).map { i -> async { repo.save(PromptKey.ANSWER, draft("$i"), "", false) } }.awaitAll()
        assertEquals((2..11).toSet(), versions.toSet())
        assertEquals(11, repo.active(PromptKey.ANSWER).version)
    }

    @Test fun emptyTemplateIsRejectedAndSystemNeverStoresASchema() = runBlocking {
        assertEquals("The template is empty", runCatching { repo.save(PromptKey.ANSWER, PromptDraft("  ", null), "", false) }.exceptionOrNull()?.message)
        repo.save(PromptKey.SYSTEM, PromptDraft("You are terse.", "{\"type\":\"object\"}"), "", false)
        assertEquals(null, repo.active(PromptKey.SYSTEM).outputSchema)
    }
}
