package com.rokid.context.voice.commands

import com.rokid.context.core.model.context.IntentLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarkCommandParserTest {
    private fun full(s: String) = MarkCommandParser.parseUtterance(s)

    /** The one command of an utterance that has no unmatched clause; null when there is no command. */
    private fun parse(s: String): MarkCommand? = full(s).let { r ->
        if (r.commands.isEmpty()) null else { assertEquals(emptyList(), r.notUnderstood, "unmatched in \"$s\""); r.commands.single() }
    }
    private fun assertNoCommand(s: String) = assertEquals(emptyList(), full(s).commands, "\"$s\" must not be a command")
    private fun assertResult(s: String, commands: List<MarkCommand>, unmatched: List<String> = emptyList()) =
        full(s).let { assertEquals(commands, it.commands, "commands of \"$s\""); assertEquals(unmatched, it.notUnderstood, "unmatched of \"$s\"") }
    private fun kind(phrase: String, intent: String? = null): MarkCommand = when (phrase) {
        in MarkPhrases.RETRO -> MarkCommand.Retro(intent)
        in MarkPhrases.UNDO -> MarkCommand.Undo
        else -> MarkCommand.Mark(intent)
    }
    private val allCommands get() = MarkPhrases.MARK + MarkPhrases.RETRO + MarkPhrases.UNDO

    @Test fun everyListedPhraseParses() {
        for (p in allCommands) {
            assertEquals(kind(p), parse(p), p)
            assertEquals(kind(p), parse("please $p"), "please $p")
            assertEquals(kind(p), parse("Hey, ${p.replaceFirstChar { it.uppercase() }}!"), "hey $p")
            assertEquals(kind(p, "driving"), parse("$p, driving"), "$p, driving")
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
        for (f in forms) assertNoCommand(f)
    }

    @Test fun canYouMarkThatParses() {
        assertEquals(MarkCommand.Mark(null), parse("can you mark that"))
        assertEquals(MarkCommand.Mark(null), parse("could you note this please"))
        assertEquals(MarkCommand.Mark("driving"), parse("could you mark that, I'm driving"))
        assertEquals(MarkCommand.Undo, parse("can you undo mark"))
        assertNoCommand("can you remember")
        assertNoCommand("can they mark that")
    }

    @Test fun forgetNeverMatches() {
        for (s in listOf("forget that", "forget this", "forget the last mark", "please forget that", "forget mark that"))
            assertNoCommand(s)
        val all = MarkPhrases.MARK + MarkPhrases.RETRO + MarkPhrases.UNDO + MarkPhrases.INTENTS.map { it.word }
        assertTrue(all.none { "forget" in it })
    }

    @Test fun intentWithoutCommandPassesThrough() {
        assertNoCommand("I'm driving")
        assertNoCommand("driving")
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
        // Unknown words in the command's own clause: a plain mark (documented). In a later clause they are unmatched.
        assertEquals(MarkCommand.Mark(null), parse("mark that was great"))
        assertEquals(MarkCommand.Mark(null), parse("mark that down as a question"))
        assertResult("mark that, baking", listOf(MarkCommand.Mark(null)), listOf("baking"))
        assertResult("mark that, I'm driving to work", listOf(MarkCommand.Mark(null)), listOf("I'm driving to work"))
    }

    @Test fun undoPhrases() {
        for (s in listOf("undo mark", "Cancel mark", "please remove mark.", "ok, undo mark")) assertEquals(MarkCommand.Undo, parse(s), s)
        assertNoCommand("undo")
    }

    @Test fun retroDoesNotStealOtherSentences() {
        assertEquals(MarkCommand.Retro(), parse("I missed that"))
        assertNoCommand("I missed that bus")
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
        assertNoCommand(""); assertNoCommand("   ..."); assertNoCommand("what's the weather")
        assertEquals(ParsedUtterance(emptyList(), emptyList()).commands, full("").commands)
        assertEquals(emptyList(), full("").notUnderstood)
    }

    // ---- round 1 negatives, all six pinned ----
    @Test fun roundOneNegativesStayUnmatched() {
        for (s in listOf("Mark called me", "what did Mark say", "am I driving too fast", "what bus goes to Andheri",
            "remind me about the meeting", "is this a plane")) {
            assertNoCommand(s)
            assertEquals(s, full(s).askText, s)
        }
    }

    // ---- ruling 1: ambiguous phrases need to stand alone ----
    @Test fun noteAndRememberWithContentFallThrough() {
        for (s in listOf("note that the meeting is at five", "remember that my car is on level 3", "remember that guy from the party",
            "save this for later", "save that file please", "bookmark this page", "capture this moment", "note this: buy milk",
            "this is important to me", "that was important wasn't it", "note that, the meeting is at five")) {
            assertNoCommand(s)
            assertEquals(s, full(s).askText, "note text must reach Ask: $s")
        }
    }

    @Test fun standalonePhrasesStillMark() {
        for (p in MarkPhrases.MARK_STANDALONE) {
            assertEquals(MarkCommand.Mark(null), parse(p), p)
            assertEquals(MarkCommand.Mark("driving"), parse("$p I'm driving"), "$p I'm driving")
            assertEquals(MarkCommand.Mark("walking"), parse("$p, walking"), "$p, walking")
            assertNoCommand("$p the meeting is at five")
        }
        assertTrue(MarkPhrases.MARK_STANDALONE.all { it in MarkPhrases.MARK })
        for (p in listOf("mark that", "mark this", "mark it")) assertEquals(MarkCommand.Mark(null), parse("$p the meeting is at five"), p)
    }

    @Test fun ambiguousCommandThenAnotherCommandIsStillAMark() {
        assertResult("note that and then undo mark", listOf(MarkCommand.Mark(null), MarkCommand.Undo))
    }

    // ---- ruling 2: per clause results ----
    @Test fun mixedMarkAndQuestion() {
        assertResult("mark that and what's this building", listOf(MarkCommand.Mark(null)), listOf("what's this building"))
        assertResult("what's this building, mark that", listOf(MarkCommand.Mark(null)), listOf("what's this building"))
        assertResult("mark that, I'm driving and what's this building", listOf(MarkCommand.Mark("driving")), listOf("what's this building"))
        assertEquals("what's this building", full("mark that and what's this building").askText)
    }

    @Test fun forgetClauseIsLeftUntouched() {
        assertResult("mark that, then forget it", listOf(MarkCommand.Mark(null)), listOf("forget it"))
        assertResult("forget that and mark that", listOf(MarkCommand.Mark(null)), listOf("forget that"))
    }

    @Test fun laterQuestionLikeIntentIsNotFolded() {
        assertResult("mark that, am I driving", listOf(MarkCommand.Mark(null)), listOf("am I driving"))
    }

    @Test fun severalCommandsInSpokenOrder() {
        assertResult("mark that and then undo mark", listOf(MarkCommand.Mark(null), MarkCommand.Undo))
        assertResult("undo mark, what did I say", listOf(MarkCommand.Undo), listOf("what did I say"))
    }

    @Test fun retroKeepsATrailingIntent() {
        assertResult("I missed that, I'm driving", listOf(MarkCommand.Retro("driving")))
        assertResult("I missed that I'm walking", listOf(MarkCommand.Retro("walking")))
    }

    // ---- ruling 4: fillers, length ----
    @Test fun extraLeadingFillers() {
        for (s in listOf("um mark that", "uh mark that", "so mark that", "okay so remember this", "um, mark that")) {
            assertEquals(MarkCommand.Mark(null), parse(s), s)
        }
        assertNoCommand("hey rokid mark that")
        assertTrue("rokid" !in MarkPhrases.FILLERS)
    }

    @Test fun overlongInputIsNotACommand() {
        val long = "mark that " + "x".repeat(600)
        val r = full(long)
        assertEquals(emptyList(), r.commands)
        assertEquals(long, r.askText)
        assertEquals(MarkCommand.Mark(null), parse("mark that" + " ".repeat(100)))
    }

    @Test fun whitespaceFloodIsFast() {
        val t0 = System.nanoTime()
        full("mark that" + " ".repeat(16_000))
        EnglishClauses.split(" ".repeat(16_000) + "mark that")
        EnglishClauses.split("mark that" + " ".repeat(16_000) + "," + " ".repeat(16_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 500, "whitespace flood took too long")
    }
}
