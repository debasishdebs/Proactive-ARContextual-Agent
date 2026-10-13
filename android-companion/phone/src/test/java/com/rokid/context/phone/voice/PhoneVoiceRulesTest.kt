package com.rokid.context.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Global Constraints: voice and model text never reach logs (the phone side of the voice path). */
class PhoneVoiceRulesTest {
    /** Preflight F6: found through the Gradle root, so a different working directory can't make the scans vacuous. */
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val phone = File(root, "phone")

    private val words = "(text|partial|question|transcript|answer|pcm|spoken|heard)"
    /** `$word`, `${...word...}`, or the word concatenated with `+` on either side (counts like `.size` are fine). */
    private val voiceText = Regex(
        "\\$\\{?$words\\b(?!\\.(size|length))|\\$\\{[^\\}]*\\b$words\\b(?!\\.(size|length))[^\\}]*\\}" +
            "|\\+\\s*(?:[A-Za-z_]\\w*\\.)*$words\\b(?!\\.(size|length))|\\b$words\\b[\\w.]*\\s*\\+",
    )

    /**
     * Each `Log.x(` call as one statement: continuation lines are joined until its parentheses balance (review minor
     * 9), so an interpolation on the next line is still seen.
     */
    private fun logStatements(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val start = Regex("\\bLog\\.[a-z]+\\(").find(lines[i])
            if (start == null) { i++; continue }
            var stmt = lines[i].substring(start.range.first)
            var depth = stmt.count { it == '(' } - stmt.count { it == ')' }
            while (depth > 0 && i + 1 < lines.size) {
                i++
                stmt += " " + lines[i].trim()
                depth += lines[i].count { it == '(' } - lines[i].count { it == ')' }
            }
            out += stmt
            i++
        }
        return out
    }

    private fun badLogLines(name: String, lines: List<String>) = logStatements(lines).filter { voiceText.containsMatchIn(it) }.map { "$name: ${it.trim()}" }
    private fun badLogLines(f: File) = badLogLines(f.name, f.readLines())

    @Test fun theScannerCatchesEveryForm() {
        val bad = listOf(
            "Log.i(TAG, \"q=\$question\")",
            "Log.i(TAG, \"a=\${r.answer}\")",
            "Log.w(TAG, \"heard: \" + transcript)",
            "Log.w(TAG, partial + \" was heard\")",
            "Log.i(TAG, \"q=\" + s.question.trim())",
            "Log.i(\n    TAG,\n    \"said \$spoken\",\n)",
        )
        bad.forEach { assertEquals(it, 1, badLogLines("bad", it.lines()).size) }
        val ok = listOf(
            "Log.i(TAG, \"listen start\")",
            "Log.i(TAG, \"n=\${text.length}\")",
            "Log.i(TAG, \"partials=\${partial.size}\")",
            "Log.w(TAG, \"failed: \${e.javaClass.simpleName}\")",
            "val s = \"q=\$question\"",
        )
        ok.forEach { assertEquals(it, 0, badLogLines("ok", it.lines()).size) }
    }

    @Test fun noLogLineInterpolatesVoiceOrAnswerText() {
        val voiceDir = File(phone, "src/main/java/com/rokid/context/phone/voice")
        val files = voiceDir.listFiles().orEmpty().filter { it.isFile && it.extension == "kt" } +
            listOf("Hub.kt", "HubActivityHook.kt", "ContextHubService.kt").map { File(phone, "src/main/java/com/rokid/context/phone/hub/$it") }
        assertTrue("no voice sources under ${voiceDir.path}", files.count { it.parentFile == voiceDir } >= 4)
        files.forEach { assertTrue("missing ${it.path}", it.isFile) }
        assertEquals(emptyList<String>(), files.flatMap(::badLogLines))
    }

    /** Preflight F12: one resume re-enters the foreground once; `VoiceGraph.onForeground` owns that call. */
    @Test fun aResumeRefreshesTheForegroundTypesOnce() {
        val hook = File(phone, "src/main/java/com/rokid/context/phone/hub/HubActivityHook.kt").readText()
        val graph = File(phone, "src/main/java/com/rokid/context/phone/voice/VoiceGraph.kt").readText()
        assertTrue("the hook hands the resume to voice", "VoiceGraph.get(it).onForeground()" in hook)
        assertEquals(0, Regex("refreshForegroundTypes\\(").findAll(hook).count())
        assertEquals(1, Regex("ContextHubService\\.refreshForegroundTypes\\(").findAll(graph).count())
    }

    /**
     * Carry (Task 7): `:services:voice` declares the TTS_SERVICE `<queries>` entry; the app's merged manifest must keep it,
     * or `TextToSpeech` sees no engine on Android 11+. Plus the hub's microphone FGS type and its permissions. The file
     * is AGP's merged main manifest, which unit-test compilation already depends on (R generation); a missing file fails.
     */
    @Test fun theMergedManifestCanSeeTtsEnginesAndHoldTheMic() {
        val file = File(phone, "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
        assertTrue("no merged manifest at ${file.path}", file.isFile)
        val merged = file.readText()
        // Review minor 3: within one <queries> block (non-greedy, never across a closing tag), inside an <intent>.
        val blocks = Regex("<queries>([\\s\\S]*?)</queries>").findAll(merged).map { it.groupValues[1] }.toList()
        assertTrue("no <queries> block", blocks.isNotEmpty())
        assertTrue(blocks.any { Regex("<intent>(?:(?!</intent>)[\\s\\S])*?android\\.intent\\.action\\.TTS_SERVICE").containsMatchIn(it) })
        assertTrue("android.permission.RECORD_AUDIO" in merged)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in merged)
        val service = Regex("<service\\b[^>]*>").findAll(merged).map { it.value }.single { "ContextHubService" in it }
        assertTrue(service, "android:foregroundServiceType=\"connectedDevice|microphone\"" in service)
    }
}
