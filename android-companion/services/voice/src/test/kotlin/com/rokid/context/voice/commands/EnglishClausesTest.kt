package com.rokid.context.voice.commands

import kotlin.test.Test
import kotlin.test.assertEquals

class EnglishClausesTest {
    @Test fun splitsOnAndThenAndCommas() {
        assertEquals(listOf("mark that", "I'm driving"), EnglishClauses.split("mark that, I'm driving"))
        assertEquals(listOf("mark that", "undo mark"), EnglishClauses.split("mark that and then undo mark"))
        assertEquals(listOf("Mark that", "note this"), EnglishClauses.split("Mark that. then note this!"))
    }

    @Test fun fillerOnlyClausesAreDropped() {
        assertEquals(listOf("mark that please"), EnglishClauses.split("hey, mark that please"))
        assertEquals(listOf("mark that"), EnglishClauses.split("okay, um, mark that, please"))
        assertEquals(emptyList(), EnglishClauses.split("hey please"))
        assertEquals(emptyList(), EnglishClauses.split(""))
    }
}
