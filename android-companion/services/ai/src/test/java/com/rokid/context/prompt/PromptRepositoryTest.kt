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
import java.util.concurrent.atomic.AtomicInteger
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
        assertEquals(false, repo.active(PromptKey.ANSWER).testedOk) // v2 was saved tested; a restored copy is untested until run
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

    private class CountingSeeds(private val text: (PromptKey) -> String = BuiltInPromptSeeds::template) : PromptSeeds {
        val reads = AtomicInteger()
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun template(key: PromptKey): String { reads.incrementAndGet(); threads += Thread.currentThread().name; return text(key) }
    }

    @Test fun seedsAreReadOffTheCallerThreadAndOnlyForUnseededKeys() = runBlocking {
        val seeds = CountingSeeds()
        val r = PromptRepository(db.promptDao(), seeds, settings) { clock++ }
        r.active(PromptKey.ANSWER)
        assertEquals(1, seeds.reads.get())
        r.ensureSeeded()
        assertEquals(PromptKey.entries.size, seeds.reads.get())
        r.ensureSeeded(); PromptKey.entries.forEach { r.active(it) }
        assertEquals(PromptKey.entries.size, seeds.reads.get())
        val caller = Thread.currentThread().name
        assertTrue(seeds.threads.toString(), seeds.threads.none { it == caller })
    }

    @Test fun versioningOffFirstSaveOverwritesTheSeededV1() = runBlocking {
        settings.setKeepVersions(false)
        assertEquals(1, repo.save(PromptKey.ANSWER, draft("x"), "edit", true))
        assertEquals(listOf(1), repo.history(PromptKey.ANSWER).first().map { it.version })
        assertTrue(repo.active(PromptKey.ANSWER).template.endsWith("x"))
    }

    @Test fun seedTextIsTrimmedSoRestoringV1IsExact() = runBlocking {
        val r = PromptRepository(db.promptDao(), CountingSeeds { "\n  " + BuiltInPromptSeeds.template(it) + "\n\n" }, settings) { clock++ }
        val v1 = r.active(PromptKey.ANSWER).template
        assertEquals(BuiltInPromptSeeds.template(PromptKey.ANSWER), v1)
        r.save(PromptKey.ANSWER, draft("a"), "", true)
        assertEquals(3, r.restore(PromptKey.ANSWER, 1))
        assertEquals(v1, r.active(PromptKey.ANSWER).template)
        assertTrue(LineDiff.diff(v1, r.active(PromptKey.ANSWER).template).all { it.kind == LineDiff.Kind.SAME })
    }

    /** A pristine earlier default (user notes: the ANSWER seed gained NOTE_ADD) becomes the new default as a new version. */
    @Test fun anUneditedPreviousDefaultIsUpgradedAsANewVersion() = runBlocking {
        db.promptDao().seedIfAbsent(PromptKey.ANSWER.name, "old answer seed", "{\"old\":true}", 1)
        val upgrading = PromptRepository(db.promptDao(), BuiltInPromptSeeds, settings, now = { clock++ },
            previousDefaults = mapOf(PromptKey.ANSWER to setOf(PromptDefaults.fingerprint("old answer seed", "{\"old\":true}"))))
        upgrading.ensureSeeded()
        val active = upgrading.active(PromptKey.ANSWER)
        assertEquals(2, active.version)
        assertEquals(BuiltInPromptSeeds.template(PromptKey.ANSWER).trim(), active.template)
        assertEquals(DefaultSchemas.text(PromptKey.ANSWER), active.outputSchema)
        assertEquals(PromptDefaults.UPDATED_NOTE, active.note)
        assertEquals("the old default stays in history", listOf(2, 1), upgrading.history(PromptKey.ANSWER).first().map { it.version })
        upgrading.active(PromptKey.ANSWER)
        assertEquals("upgraded once", 2, upgrading.history(PromptKey.ANSWER).first().size)
    }

    @Test fun anEditedPromptIsLeftAlone() = runBlocking {
        db.promptDao().seedIfAbsent(PromptKey.ANSWER.name, "old answer seed", "{\"old\":true}", 1)
        db.promptDao().appendAndActivate(PromptKey.ANSWER.name, "old answer seed, edited", "{\"old\":true}", "mine", true, 2)
        val upgrading = PromptRepository(db.promptDao(), BuiltInPromptSeeds, settings, now = { clock++ },
            previousDefaults = mapOf(PromptKey.ANSWER to setOf(PromptDefaults.fingerprint("old answer seed", "{\"old\":true}"))))
        upgrading.ensureSeeded()
        val active = upgrading.active(PromptKey.ANSWER)
        assertEquals(2, active.version)
        assertEquals("old answer seed, edited", active.template)
        // An edited schema alone also counts as an edit.
        db.promptDao().appendAndActivate(PromptKey.ANSWER.name, "old answer seed", "{\"old\":false}", "mine", true, 3)
        val again = PromptRepository(db.promptDao(), BuiltInPromptSeeds, settings, now = { clock++ },
            previousDefaults = mapOf(PromptKey.ANSWER to setOf(PromptDefaults.fingerprint("old answer seed", "{\"old\":true}"))))
        assertEquals("{\"old\":false}", again.active(PromptKey.ANSWER).outputSchema)
    }

    @Test fun versioningOffUpgradesInPlace() = runBlocking {
        db.promptDao().seedIfAbsent(PromptKey.ANSWER.name, "old answer seed", "{\"old\":true}", 1)
        settings.setKeepVersions(false)
        val upgrading = PromptRepository(db.promptDao(), BuiltInPromptSeeds, settings, now = { clock++ },
            previousDefaults = mapOf(PromptKey.ANSWER to setOf(PromptDefaults.fingerprint("old answer seed", "{\"old\":true}"))))
        val active = upgrading.active(PromptKey.ANSWER)
        assertEquals(1, active.version)
        assertEquals(BuiltInPromptSeeds.template(PromptKey.ANSWER).trim(), active.template)
    }

    /** A save that lands while the migration is loading its seed always wins: the migration is a compare-and-set against the text it inspected. */
    @Test fun aSaveBetweenInspectAndWriteIsNotOverwrittenByTheUpgrade() = runBlocking {
        for (keep in listOf(true, false)) {
            val d = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
            try {
                settings.setKeepVersions(keep)
                d.promptDao().seedIfAbsent(PromptKey.ANSWER.name, "old answer seed", "{\"old\":true}", 1)
                val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
                val slow = PromptSeeds { k -> entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); BuiltInPromptSeeds.template(k) }
                val upgrading = PromptRepository(d.promptDao(), slow, settings, now = { clock++ },
                    previousDefaults = mapOf(PromptKey.ANSWER to setOf(PromptDefaults.fingerprint("old answer seed", "{\"old\":true}"))))
                val migration = async(kotlinx.coroutines.Dispatchers.Default) { upgrading.active(PromptKey.ANSWER) }
                check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) // the migration has inspected the old default and is suspended loading the seed
                upgrading.save(PromptKey.ANSWER, draft("custom"), "mine", true)
                release.countDown()
                migration.await()
                val active = upgrading.active(PromptKey.ANSWER)
                assertTrue("keep=$keep", active.template.endsWith("custom")); assertEquals("mine", active.note)
            } finally { d.close() }
        }
    }
}
