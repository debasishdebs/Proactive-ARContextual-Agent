package com.rokid.context.voice.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanguagePackTest {
    @Test fun defaultLocaleIsEnglishIndia() {
        assertEquals("en-IN", LanguageRegistry.DEFAULT_LOCALE)
        assertNotNull(LanguageRegistry.forLocale("en-IN"))
    }

    @Test fun englishPacksParseMarkCommands() {
        for (locale in listOf("en-IN", "en-US", "en-GB")) {
            val pack = LanguageRegistry.forLocale(locale)!!
            assertEquals(MarkCommand.Mark("driving"), pack.parseCommand("mark that, I'm driving"))
            assertEquals(MarkCommand.Undo, pack.parseCommand("undo mark"))
        }
    }

    @Test fun unknownLocaleHasNoPack() = assertNull(LanguageRegistry.forLocale("hi-IN"))
}
