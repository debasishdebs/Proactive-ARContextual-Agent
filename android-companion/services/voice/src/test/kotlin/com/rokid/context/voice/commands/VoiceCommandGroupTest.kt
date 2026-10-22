package com.rokid.context.voice.commands

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceCommandGroupTest {
    @Test fun everyMarkCommandHasAGroup() {
        assertEquals(VoiceCommandGroup.Mark, VoiceCommandGroup.of(MarkCommand.Mark(null)))
        assertEquals(VoiceCommandGroup.Mark, VoiceCommandGroup.of(MarkCommand.Mark("driving")))
        assertEquals(VoiceCommandGroup.Retro, VoiceCommandGroup.of(MarkCommand.Retro))
        assertEquals(VoiceCommandGroup.Undo, VoiceCommandGroup.of(MarkCommand.Undo))
    }

    @Test fun disabledGroupsBlockTheirCommandsOnly() {
        val off = setOf(VoiceCommandGroup.Undo)
        assertFalse(VoiceCommandGroup.isAllowed(MarkCommand.Undo, off))
        assertTrue(VoiceCommandGroup.isAllowed(MarkCommand.Retro, off))
        assertTrue(VoiceCommandGroup.isAllowed(MarkCommand.Mark(null), off))
    }

    @Test fun withEnabledEncodeDecodeRoundTrip() {
        val off = VoiceCommandGroup.withEnabled(emptySet(), VoiceCommandGroup.Retro, enabled = false)
        assertEquals(setOf(VoiceCommandGroup.Retro), off)
        assertEquals(emptySet(), VoiceCommandGroup.withEnabled(off, VoiceCommandGroup.Retro, enabled = true))
        assertEquals(off, VoiceCommandGroup.decode(VoiceCommandGroup.encode(off)))
        assertEquals(emptySet(), VoiceCommandGroup.decode("Bogus,,Nope"))
    }

    @Test fun examplesComeFromThePhraseTable() {
        assertTrue(MarkPhrases.MARK.first() in VoiceCommandGroup.Mark.examples)
        assertTrue(MarkPhrases.RETRO.first() in VoiceCommandGroup.Retro.examples)
        assertTrue(MarkPhrases.UNDO.first() in VoiceCommandGroup.Undo.examples)
    }

    @Test fun gateRunsAllowedAndToastsBlocked() = runTest {
        val ran = mutableListOf<MarkCommand>(); val toasts = mutableListOf<String>()
        val gate = VoiceCommandGate({ setOf(VoiceCommandGroup.Undo) }, { toasts += it }, { ran += it })
        gate(MarkCommand.Mark(null)); gate(MarkCommand.Undo)
        assertEquals(listOf<MarkCommand>(MarkCommand.Mark(null)), ran)
        assertEquals(listOf(VoiceCommandGroup.Undo.blockedMessage), toasts)
    }
}
