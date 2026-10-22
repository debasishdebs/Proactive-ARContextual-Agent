package com.rokid.context.voice.commands

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceMarksDocTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "docs/voice-marks.md").exists() || File(it, "README.md").exists() && File(it, "docs").isDirectory }
    private val docFile = File(root, "docs/voice-marks.md")
    private val readmeFile = File(root, "README.md")

    private fun block(text: String, begin: String, end: String): String {
        val b = text.indexOf(begin); val e = text.indexOf(end)
        assertTrue(b >= 0 && e > b, "markers $begin / $end missing")
        return text.substring(b + begin.length, e).trim('\n') + "\n"
    }

    private fun maybeRegen(file: File, begin: String, end: String, body: String) {
        if (System.getenv("VOICE_MARKS_REGEN") != "1") return
        val t = file.readText()
        block(t, begin, end) // fails with a clear message when a marker is missing
        val b = t.indexOf(begin); val e = t.indexOf(end)
        file.writeText(t.substring(0, b + begin.length) + "\n" + body + t.substring(e))
    }

    private fun docBlock(): String {
        maybeRegen(docFile, MarkPhrasesDoc.DOC_BEGIN, MarkPhrasesDoc.DOC_END, MarkPhrasesDoc.render())
        return block(docFile.readText(), MarkPhrasesDoc.DOC_BEGIN, MarkPhrasesDoc.DOC_END)
    }

    private fun tablePhrases(): Set<String> =
        (MarkPhrases.MARK + MarkPhrases.RETRO + MarkPhrases.UNDO + MarkPhrases.FILLERS + MarkPhrases.INTENT_FILLERS +
            MarkPhrases.NEGATIONS + MarkPhrases.QUESTION_WORDS + MarkPhrases.INTENTS.map { it.word } +
            MarkPhrases.POLITE_PREFIXES.map { it.joinToString(" ") }).toSet()

    @Test fun docBlockEqualsRender() = assertEquals(MarkPhrasesDoc.render(), docBlock())

    @Test fun readmeBlockEqualsRenderCompact() {
        maybeRegen(readmeFile, MarkPhrasesDoc.README_BEGIN, MarkPhrasesDoc.README_END, MarkPhrasesDoc.renderCompact())
        assertEquals(MarkPhrasesDoc.renderCompact(), block(readmeFile.readText(), MarkPhrasesDoc.README_BEGIN, MarkPhrasesDoc.README_END))
    }

    @Test fun everyPhraseInTableAppearsInDoc() {
        val doc = docBlock()
        val missing = tablePhrases().filter { "`$it`" !in doc }
        assertTrue(missing.isEmpty(), "phrases missing from docs/voice-marks.md: $missing")
    }

    @Test fun noPhraseInDocMissingFromTable() {
        val inDoc = Regex("`([^`]+)`").findAll(docBlock()).map { it.groupValues[1] }.toSet()
        val extra = inDoc - tablePhrases()
        assertTrue(extra.isEmpty(), "docs/voice-marks.md lists phrases not in MarkPhrases: $extra")
        assertTrue(inDoc.none { "forget" in it }, "no backticked token may mention forget")
        assertTrue("forget" !in docBlock().lowercase(), "the generated block must not mention forget at all")
    }

    @Test fun docMentionsPrivacyAndUnsupported() {
        val doc = docFile.readText()
        listOf(
            "Audio is never stored or uploaded.",
            "Recognition runs on the phone's on-device recogniser only.",
            "Only the time, source and label are kept.",
            "Hindi and other languages are not supported yet.",
            "The word \"forget\" is never a mark command",
            "There is no hotword and no always-on listening.",
            "Unknown words in a mark command's own sentence save a plain mark.",
            "Voice marks are always on in this build.",
        ).forEach { assertTrue(it in doc, "missing sentence: $it") }
    }

    @Test fun docDoesNotPromiseThingsThisBuildLacks() {
        val doc = docFile.readText() + readmeFile.readText().substringAfter("## Voice marks").substringBefore("## Project Tracking")
        listOf("switch off Voice marks", "turn them off, open Settings", "on by default", "Or use the phone notification", "does nothing")
            .forEach { assertTrue(it !in doc, "doc still says: $it") }
        assertTrue("not available yet" in doc)
    }
}
