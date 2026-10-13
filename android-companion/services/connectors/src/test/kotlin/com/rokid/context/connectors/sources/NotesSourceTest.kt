package com.rokid.context.connectors.sources

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NotesSourceTest {
    @Test fun shoppingThenNotes() = runBlocking {
        val s = NotesSource(
            shopping = { listOf(ShoppingLineRow("milk", 2.0, "l"), ShoppingLineRow("bread", null, null)) },
            notes = { listOf("n".repeat(250)) },
        )
        val lines = s.lines(null, 1L)
        assertEquals("shopping \u00b7 milk \u00d72l", lines[0])
        assertEquals("shopping \u00b7 bread", lines[1])
        assertEquals("note \u00b7 " + "n".repeat(200), lines[2])
    }
}
