package com.rokid.context.prompt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.DefaultSchemas
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.db.CasOutcome
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PromptRepositoryCasTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val settings = InMemoryPromptSettings()
    private var clock = 1_000L
    private val repo = PromptRepository(db.promptDao(), BuiltInPromptSeeds, settings) { clock++ }
    @After fun close() = db.close()

    private fun draft(t: String) = PromptDraft("Q: {{question}} M: {{retrieved_memories}} $t", DefaultSchemas.text(PromptKey.ANSWER))

    @Test fun normalisesLikeSave() = runBlocking {
        val a = repo.active(PromptKey.ANSWER)
        val expected = PromptDraft("  ${a.template}\n\n", "  ${a.outputSchema}  ")
        val out = repo.saveAllIfUnchanged(listOf(CasEdit(PromptKey.ANSWER, expected, PromptDraft("  new text \n", "  {} "), "  a \n  b  ", true)))
        assertEquals(CasOutcome.Saved(mapOf("ANSWER" to a.version + 1)), out)
        val n = repo.active(PromptKey.ANSWER)
        assertEquals("new text", n.template); assertEquals("{}", n.outputSchema); assertEquals("a b", n.note)
    }

    @Test fun seedsBeforeCompare() = runBlocking {
        val seed = BuiltInPromptSeeds.template(PromptKey.ANSWER).trim()
        val out = repo.saveAllIfUnchanged(listOf(CasEdit(PromptKey.ANSWER, PromptDraft(seed, DefaultSchemas.text(PromptKey.ANSWER)), draft("x"), "", false)))
        assertEquals(CasOutcome.Saved(mapOf("ANSWER" to 2)), out) // fresh db: no row yet, seeded as v1 then v2 appended
    }

    @Test fun systemSchemaIgnored() = runBlocking {
        val a = repo.active(PromptKey.SYSTEM)
        assertNull(a.outputSchema)
        val out = repo.saveAllIfUnchanged(listOf(CasEdit(PromptKey.SYSTEM, PromptDraft(a.template, "junk"), PromptDraft("sys {{x}}", "junk2"), "", true)))
        assertEquals(CasOutcome.Saved(mapOf("SYSTEM" to a.version + 1)), out)
        assertNull(repo.active(PromptKey.SYSTEM).outputSchema)
    }
}
