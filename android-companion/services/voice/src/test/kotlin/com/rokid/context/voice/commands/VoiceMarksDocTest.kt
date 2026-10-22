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
        assertTrue("forget" !in inDoc)
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
            "Unknown words save a plain mark.",
        ).forEach { assertTrue(it in doc, "missing sentence: $it") }
    }
}
