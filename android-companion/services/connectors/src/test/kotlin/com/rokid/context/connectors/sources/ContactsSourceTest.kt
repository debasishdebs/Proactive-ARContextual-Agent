package com.rokid.context.connectors.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsSourceTest {
    private val book = listOf("\u0930\u093e\u0939\u0941\u0932 \u0935\u0930\u094d\u092e\u093e", "Priya Singh", "Mom", "Anil Kapoor", "Sapriya Das")
    private val asked = mutableListOf<List<String>>()
    private val source = ContactsSource { tokens -> asked += tokens; book }

    @Test fun matchesHindiAndEnglishNames() = runBlocking {
        val lines = source.lines("call \u0930\u093e\u0939\u0941\u0932 and Priya?", 1L)
        assertEquals(listOf("contact \u00b7 \u0930\u093e\u0939\u0941\u0932 \u0935\u0930\u094d\u092e\u093e", "contact \u00b7 Priya Singh"), lines)
        assertTrue(lines.none { l -> l.any { it.isDigit() } })
        assertTrue("short tokens are not sent", asked.single().none { it.length < 3 })
    }

    @Test fun noQuestionNoContacts() = runBlocking {
        assertEquals(emptyList<String>(), source.lines(null, 1L))
        assertTrue(asked.isEmpty())
    }

    @Test fun atMostThree() = runBlocking {
        val many = ContactsSource { listOf("A", "B", "C", "D", "E").map { "Priya $it" } }
        assertEquals(3, many.lines("priya", 1L).size)
    }
}
