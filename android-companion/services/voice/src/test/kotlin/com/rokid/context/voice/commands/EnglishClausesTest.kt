package com.rokid.context.voice.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun sentencePunctuationSplitsOnlyBeforeASpace() {
        assertEquals(listOf("Mark that", "I'm driving"), EnglishClauses.split("Mark that! I'm driving."))
        assertEquals(listOf("go 3.5 km"), EnglishClauses.split("go 3.5 km"))
    }

    @Test fun whitespaceRunsAreLinear() {
        val t0 = System.nanoTime()
        EnglishClauses.split(" ".repeat(16_000) + "x")
        EnglishClauses.split("a" + " ".repeat(16_000) + "," + " ".repeat(16_000) + "b")
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500)
    }
}
