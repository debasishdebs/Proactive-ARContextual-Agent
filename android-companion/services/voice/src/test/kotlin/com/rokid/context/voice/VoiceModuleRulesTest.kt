package com.rokid.context.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** R-01 + Global Constraints: :services:voice depends only on :core:model, never uses a network recognizer, never logs voice text. */
class VoiceModuleRulesTest {
    /** Preflight F6: found through the Gradle root, so a different working directory can't make the scans vacuous. */
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val module = File(root, "services/voice")
    private val main = File(module, "src/main/kotlin")

    private fun sources(): List<File> {
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources under ${main.path}")
        return files
    }

    @Test fun dependsOnlyOnCoreModel() {
        val gradle = File(module, "build.gradle.kts").readText()
        assertEquals(setOf(":core:model"), Regex("project\\(\"([^\"]+)\"\\)").findAll(gradle).map { it.groupValues[1] }.toSet())
        val bad = sources().filter { f -> f.readLines().any { l -> listOf("phone", "ai", "memory", "orchestration", "link").any { l.startsWith("import com.rokid.context.$it.") } } }
        assertEquals(emptyList(), bad.map { it.name })
    }

    @Test fun onlyTheOnDeviceRecognizerIsEverCreated() {
        val bad = sources().filter { "createSpeechRecognizer(" in it.readText() }
        assertEquals(emptyList(), bad.map { it.name })
    }

    /** Log lines carry event names, counts and class names: never a transcript, partial, question or answer. */
    @Test fun noLogLineInterpolatesVoiceText() {
        val text = Regex("\\$\\{?(text|partial|question|transcript|answer|results|spoken)\\b")
        val bad = sources().flatMap { f -> f.readLines().filter { l -> ("Log." in l || "log(" in l) && text.containsMatchIn(l) }.map { "${f.name}: ${it.trim()}" } }
        assertEquals(emptyList(), bad)
    }
}
