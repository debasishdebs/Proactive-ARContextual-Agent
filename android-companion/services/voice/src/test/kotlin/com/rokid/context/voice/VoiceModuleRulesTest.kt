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

    /** Every project dependency a build file names: `project(":x")`, `project(path = ":x")`, `projects.x` accessors. */
    private fun projectDeps(gradle: String): Set<String> {
        val named = Regex("\\bproject\\(\\s*(?:path\\s*=\\s*)?\"([^\"]+)\"").findAll(gradle).map { it.groupValues[1] }.toList()
        val accessors = Regex("\\bprojects\\.([A-Za-z0-9_.]+)").findAll(gradle).map { "projects." + it.groupValues[1] }.toList()
        val calls = Regex("\\bproject\\(").findAll(gradle).count()
        val unparsed = if (calls > named.size) listOf("unparsed project(...)") else emptyList()
        return (named + accessors + unparsed).toSet()
    }

    /** An import of any `com.rokid.context` package other than `:core:model`'s and this module's (phone root included). */
    private fun foreignImport(line: String): Boolean {
        val l = line.trim()
        return l.startsWith("import com.rokid.context.") &&
            !l.startsWith("import com.rokid.context.core.model.") && !l.startsWith("import com.rokid.context.voice.")
    }

    /** `createSpeechRecognizer(...)` or a `::createSpeechRecognizer` reference; `createOnDeviceSpeechRecognizer` is fine. */
    private fun createsNetworkRecognizer(source: String): Boolean = Regex("\\bcreateSpeechRecognizer\\b").containsMatchIn(source)

    private val voiceWord = Regex("(?i)(text|partial|question|transcript|answer|result|spoken|utterance|heard)")
    private val countOnly = Regex("\\.(size|length|count\\(\\)|isEmpty\\(\\)|isNotEmpty\\(\\)|isBlank\\(\\))$")

    /** A log line that puts voice text into the message: `$name`, `${expr}` or `+ name` concatenation; counts are fine. */
    private fun logsVoiceText(line: String): Boolean {
        if (!("Log." in line || "log(" in line)) return false
        val exprs = Regex("\\$([A-Za-z_]\\w*)").findAll(line).map { it.groupValues[1] } +
            Regex("\\$\\{([^}]*)\\}").findAll(line).map { it.groupValues[1].trim() } +
            Regex("\\+\\s*([A-Za-z_][\\w.]*)").findAll(line).map { it.groupValues[1] } +
            Regex("([A-Za-z_][\\w.]*)\\s*\\+").findAll(line).map { it.groupValues[1] }
        return exprs.any { e -> !countOnly.containsMatchIn(e) && voiceWord.containsMatchIn(e) }
    }

    @Test fun theScannersCatchEveryForm() {
        assertEquals(setOf(":core:model", ":services:ai", "projects.services.memory"), projectDeps(
            "api(project(\":core:model\"))\nimplementation(project(path = \":services:ai\"))\nimplementation(projects.services.memory)",
        ))
        listOf("phone.hub.Hub", "ai.ModelRouter", "memory.MemoryStore", "orchestration.X", "link.GlassesLink", "data.runtime.X", "domain.queue.X", "ContextApp", "glasses.X")
            .forEach { assertTrue(foreignImport("import com.rokid.context.$it"), it) }
        listOf("core.model.audio.Pcm", "core.model.TextHygiene", "voice.PipeSink")
            .forEach { assertTrue(!foreignImport("import com.rokid.context.$it"), it) }
        assertTrue(createsNetworkRecognizer("SpeechRecognizer.createSpeechRecognizer(context)"))
        assertTrue(createsNetworkRecognizer("val make = SpeechRecognizer::createSpeechRecognizer"))
        assertTrue(!createsNetworkRecognizer("SpeechRecognizer.createOnDeviceSpeechRecognizer(context)"))
        listOf(
            "Log.d(TAG, \"final \$finalText\")",
            "Log.i(TAG, \"got \${r.text}\")",
            "Log.w(TAG, \"heard: \" + spoken)",
            "log(\"q=\" + question.trim())",
            "Log.d(TAG, \"partial=\$partial\")",
        ).forEach { assertTrue(logsVoiceText(it), it) }
        listOf(
            "Log.d(TAG, \"partials=\${partials.size}\")",
            "Log.i(TAG, \"event=start count=\$count\")",
            "Log.w(TAG, \"failed: \${e.javaClass.simpleName}\")",
            "val s = \"x \$text\"",
        ).forEach { assertTrue(!logsVoiceText(it), it) }
    }

    @Test fun dependsOnlyOnCoreModel() {
        assertEquals(setOf(":core:model"), projectDeps(File(module, "build.gradle.kts").readText()))
        val bad = sources().filter { f -> f.readLines().any(::foreignImport) }
        assertEquals(emptyList(), bad.map { it.name })
    }

    @Test fun onlyTheOnDeviceRecognizerIsEverCreated() {
        val bad = sources().filter { createsNetworkRecognizer(it.readText()) }
        assertEquals(emptyList(), bad.map { it.name })
    }

    /** Spec §3.6 / R-73: the speaker has the answer text in hand and logs nothing at all. */
    @Test fun theSpeakerNeverLogs() {
        val files = listOf("Speaker.kt", "android/AndroidSpeaker.kt", "android/AudioRoutes.kt").map { File(main, "com/rokid/context/voice/$it") }
        files.forEach { assertTrue(it.isFile, "missing ${it.path}") }
        val logging = Regex("\\bLog\\.|\\blog\\(|SafeLog|println\\(|printStackTrace")
        val bad = files.flatMap { f -> f.readLines().filter { logging.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" } }
        assertEquals(emptyList(), bad)
    }

    /** Android 11+ package visibility: without this query `TextToSpeech` sees no engine and never initialises. */
    @Test fun theManifestCanSeeTtsEngines() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("<queries>[\\s\\S]*android\\.intent\\.action\\.TTS_SERVICE[\\s\\S]*</queries>").containsMatchIn(manifest))
    }

    /** Log lines carry event names, counts and class names: never a transcript, partial, question or answer. */
    @Test fun noLogLineInterpolatesVoiceText() {
        val bad = sources().flatMap { f -> f.readLines().filter(::logsVoiceText).map { "${f.name}: ${it.trim()}" } }
        assertEquals(emptyList(), bad)
    }
}
