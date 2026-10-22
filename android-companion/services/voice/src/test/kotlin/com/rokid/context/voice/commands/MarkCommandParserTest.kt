package com.rokid.context.voice.commands

import com.rokid.context.core.model.context.IntentLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkCommandParserTest {
    private fun parse(s: String) = MarkCommandParser.parse(s)
    private fun kind(phrase: String): MarkCommand? = when (phrase) {
        in MarkPhrases.RETRO -> MarkCommand.Retro
        in MarkPhrases.UNDO -> MarkCommand.Undo
        else -> MarkCommand.Mark(null)
    }
    private val allCommands get() = MarkPhrases.MARK + MarkPhrases.RETRO + MarkPhrases.UNDO

    @Test fun everyListedPhraseParses() {
        for (p in allCommands) {
            assertEquals(kind(p), parse(p), p)
            assertEquals(kind(p), parse("please $p"), "please $p")
            assertEquals(kind(p), parse("Hey, ${p.replaceFirstChar { it.uppercase() }}!"), "hey $p")
            val withIntent = parse("$p, driving")
            val expected = if (p in MarkPhrases.MARK) MarkCommand.Mark("driving") else kind(p)
            assertEquals(expected, withIntent, "$p, driving")
        }
    }

    @Test fun everyIntentWordAfterCommandParses() {
        for (i in MarkPhrases.INTENTS) {
            assertEquals(MarkCommand.Mark(i.canvasLabel), parse("mark that, ${i.word}"), i.word)
            assertEquals(MarkCommand.Mark(i.canvasLabel), parse("mark that, I'm ${i.word}"), "I'm ${i.word}")
            assertEquals(MarkCommand.Mark(i.canvasLabel), parse("mark that, we're just currently ${i.word}"), "we're ${i.word}")
            assertEquals(MarkCommand.Mark(i.canvasLabel), parse("mark that I am ${i.word}"), "I am ${i.word}")
        }
    }

    @Test fun questionFormsNeverParse() {
        val forms = mutableListOf("is this important", "was that important", "should I save this", "how do I bookmark this",
            "can I note this", "where did I save that", "why did I miss that", "what did I miss", "what did I save", "what did Mark say",
            "Mark called me", "mark my words", "did I miss that")
        for (c in allCommands) { forms += "did you $c"; forms += "do you $c"; forms += "would you $c"; forms += "what if I $c" }
        for (f in forms) assertNull(parse(f), f)
    }

    @Test fun canYouMarkThatParses() {
        assertEquals(MarkCommand.Mark(null), parse("can you mark that"))
        assertEquals(MarkCommand.Mark(null), parse("could you note this please"))
        assertEquals(MarkCommand.Mark("driving"), parse("could you mark that, I'm driving"))
        assertEquals(MarkCommand.Undo, parse("can you undo mark"))
        assertNull(parse("can you remember"))
        assertNull(parse("can they mark that"))
    }

    @Test fun forgetNeverMatches() {
        for (s in listOf("forget that", "forget this", "forget the last mark", "please forget that", "forget mark that"))
            assertNull(parse(s), s)
        val all = MarkPhrases.MARK + MarkPhrases.RETRO + MarkPhrases.UNDO + MarkPhrases.INTENTS.map { it.word }
        assertTrue(all.none { "forget" in it })
    }

    @Test fun intentWithoutCommandPassesThrough() {
        assertNull(parse("I'm driving"))
        assertNull(parse("driving"))
    }

    @Test fun negatedIntentPlainMark() {
        assertEquals(MarkCommand.Mark(null), parse("mark that, I'm not driving"))
        assertEquals(MarkCommand.Mark(null), parse("mark that not walking"))
    }

    @Test fun twoIntentsPlainMark() {
        assertEquals(MarkCommand.Mark(null), parse("mark that, driving and walking"))
        assertEquals(MarkCommand.Mark(null), parse("mark that, reading, sitting"))
    }

    @Test fun unknownWordPlainMark() {
        assertEquals(MarkCommand.Mark(null), parse("mark that, baking"))
        assertEquals(MarkCommand.Mark(null), parse("mark that, I'm driving to work"))
        assertEquals(MarkCommand.Mark(null), parse("mark that was great"))
    }

    @Test fun undoPhrases() {
        for (s in listOf("undo mark", "Cancel mark", "please remove mark.", "ok, undo mark")) assertEquals(MarkCommand.Undo, parse(s), s)
        assertNull(parse("undo"))
    }

    @Test fun retroDoesNotStealOtherSentences() {
        assertEquals(MarkCommand.Retro, parse("I missed that"))
        assertNull(parse("I missed that bus"))
    }

    @Test fun everyIntentHasFinalIntentLabel() {
        val names = IntentLabel.entries.map { it.name }.toSet()
        for (i in MarkPhrases.INTENTS) assertTrue(i.intentLabel in names, "${i.word} -> ${i.intentLabel}")
        assertEquals(setOf("SITTING_IDLE", "WORKOUT", "COMPUTER_WORK", "DRIVING", "VEHICLE_PASSENGER", "FLIGHT_CABIN", "WALKING", "OTHER"),
            MarkPhrases.INTENTS.map { it.intentLabel }.toSet())
        assertEquals("COMPUTER_WORK", MarkPhrases.intentFor("reading")!!.intentLabel)
        assertEquals("COMPUTER_WORK", MarkPhrases.intentFor("writing")!!.intentLabel)
        assertEquals("VEHICLE_PASSENGER", MarkPhrases.intentFor("travelling")!!.intentLabel)
    }

    @Test fun ridingMapsToOther() = assertEquals("OTHER", MarkPhrases.intentFor("riding")!!.intentLabel)

    @Test fun curlyApostropheIsNormalised() = assertEquals(MarkCommand.Mark("driving"), parse("mark that, I\u2019m driving"))

    @Test fun emptyAndNoise() {
        assertNull(parse(""))
        assertNull(parse("   ..."))
        assertNull(parse("what's the weather"))
    }
}
