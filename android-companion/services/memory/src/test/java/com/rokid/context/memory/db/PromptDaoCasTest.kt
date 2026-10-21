package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PromptDaoCasTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.promptDao()
    @After fun close() = db.close()

    @Before fun seed() = runBlocking<Unit> {
        dao.seedIfAbsent("A", "a0", "sa", 1); dao.seedIfAbsent("B", "b0", null, 1)
    }

    private fun w(id: String, expT: String, expS: String?, t: String, s: String?) = CasWrite(id, expT, expS, t, s, "n", true)

    @Test fun matchWritesAppend() = runBlocking {
        val out = dao.casSaveAll(listOf(w("A", "a0", "sa", "a1", "sa1"), w("B", "b0", null, "b1", null)), keepVersions = true, now = 5)
        assertEquals(CasOutcome.Saved(mapOf("A" to 2, "B" to 2)), out)
        assertEquals("a1", dao.active("A")!!.template); assertEquals("sa1", dao.active("A")!!.outputSchema)
        assertEquals("a0", dao.version("A", 1)!!.template)
        assertEquals("b1", dao.active("B")!!.template)
    }

    @Test fun matchWritesOverwriteWhenVersioningOff() = runBlocking {
        val out = dao.casSaveAll(listOf(w("A", "a0", "sa", "a1", "sa")), keepVersions = false, now = 5)
        assertEquals(CasOutcome.Saved(mapOf("A" to 1)), out)
        assertEquals("a1", dao.active("A")!!.template); assertEquals(0, dao.maxVersion("A") - 1)
    }

    @Test fun mismatchWritesNothing() = runBlocking {
        val out = dao.casSaveAll(listOf(w("A", "OTHER", "sa", "a1", "sa")), keepVersions = true, now = 5)
        assertEquals(CasOutcome.Mismatch(listOf("A")), out)
        assertEquals("a0", dao.active("A")!!.template); assertEquals(1, dao.maxVersion("A"))
    }

    @Test fun secondKeyMismatchRollsBackFirst() = runBlocking {
        val out = dao.casSaveAll(listOf(w("A", "a0", "sa", "a1", "sa"), w("B", "b0", "WRONG-SCHEMA", "b1", null)), keepVersions = true, now = 5)
        assertEquals(CasOutcome.Mismatch(listOf("B")), out)
        assertEquals("a0", dao.active("A")!!.template); assertEquals(1, dao.maxVersion("A"))
        assertEquals("b0", dao.active("B")!!.template); assertEquals(1, dao.maxVersion("B"))
    }

    @Test fun nullSchemaEqualsNull() = runBlocking {
        assertEquals(CasOutcome.Saved(mapOf("B" to 2)), dao.casSaveAll(listOf(w("B", "b0", null, "b1", null)), true, 5))
        assertEquals(CasOutcome.Mismatch(listOf("B")), dao.casSaveAll(listOf(w("B", "b1", "", "b2", null)), true, 6)) // "" is not null
        assertNull(dao.active("B")!!.outputSchema)
    }
}
