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

    /** Names `import android.util.Log as X` gives the logger in a file (fix round 1: aliases log too). */
    private fun logAliases(source: String): Set<String> =
        Regex("(?m)^\\s*import\\s+android\\.util\\.Log\\s+as\\s+(\\w+)").findAll(source).map { it.groupValues[1] }.toSet()

    /** Any logging call: `Log.`, `log(`, Timber, `System.out/err`, `println(`, or an aliased `Log`. */
    private fun isLogLine(line: String, aliases: Set<String> = emptySet()): Boolean =
        Regex("\\bLog\\.|\\blog\\(|\\bTimber\\b|\\bSystem\\.(out|err)\\b|\\bprintln\\(|\\bprintStackTrace\\(").containsMatchIn(line) ||
            aliases.any { Regex("\\b${Regex.escape(it)}\\.").containsMatchIn(line) }

    /** A log line that puts voice text into the message: `$name`, `${expr}` or `+ name` concatenation; counts are fine. */
    private fun logsVoiceText(line: String, aliases: Set<String> = emptySet()): Boolean {
        if (!isLogLine(line, aliases)) return false
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
            "Timber.d(\"heard \$text\")",
            "System.out.println(\"q=\" + question)",
            "System.err.print(answer + \"!\")",
            "println(\"spoken \$spoken\")",
        ).forEach { assertTrue(logsVoiceText(it), it) }
        assertEquals(setOf("L"), logAliases("package x\nimport android.util.Log as L\n"))
        assertTrue(logsVoiceText("L.i(TAG, \"said \$text\")", setOf("L")))
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

    /**
     * Spec §3.6 / R-73: the speaker holds the answer text, so it logs fixed [SpeakerEvent] names only (fix round 1):
     * `log(SpeakerEvent.X)` in the core, one `Log.i(TAG, it.wire)` sink in AndroidSpeaker, nothing else.
     */
    private fun speakerLogAllowed(line: String): Boolean =
        Regex("\\blog\\(SpeakerEvent\\.[A-Z_]+\\)").containsMatchIn(line) || line.trim().endsWith("{ Log.i(TAG, it.wire) },") ||
            Regex("^\\s*private val log: \\(SpeakerEvent\\) -> Unit").containsMatchIn(line)

    private fun speakerLogViolations(name: String, source: String): List<String> {
        val aliases = logAliases(source)
        val aliased = if (aliases.isEmpty()) emptyList() else listOf("$name: aliased android.util.Log")
        return aliased + source.lines().filter { isLogLine(it, aliases) && !speakerLogAllowed(it) }.map { "$name: ${it.trim()}" }
    }

    @Test fun theSpeakerLogsOnlyFixedEventNames() {
        val files = listOf("Speaker.kt", "android/AndroidSpeaker.kt", "android/AudioRoutes.kt").map { File(main, "com/rokid/context/voice/$it") }
        files.forEach { assertTrue(it.isFile, "missing ${it.path}") }
        assertTrue(files.any { f -> f.readLines().any { "log(SpeakerEvent." in it } }, "the events are logged somewhere")
        val bad = files.flatMap { speakerLogViolations(it.name, it.readText()) }
        assertEquals(emptyList(), bad)
    }

    @Test fun theSpeakerLogRuleCatchesEveryForm() {
        listOf(
            "log(SpeakerEvent.INIT_FAILED)",
            "        private val log: (SpeakerEvent) -> Unit = { Log.i(TAG, it.wire) },",
        ).forEach { assertEquals(emptyList(), speakerLogViolations("ok", it), it) }
        listOf(
            "Log.i(TAG, \"speaking \$id\")",
            "log(\"lang_missing \$locale\")",
            "Timber.i(\"focus_lost\")",
            "System.out.println(text)",
            "System.err.println(\"x\")",
            "println(event)",
            "import android.util.Log as L\nL.i(TAG, \"x\")",
        ).forEach { assertTrue(speakerLogViolations("bad", it).isNotEmpty(), it) }
    }

    /** Android 11+ package visibility: without this query `TextToSpeech` sees no engine and never initialises. */
    @Test fun theManifestCanSeeTtsEngines() {
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("<queries>[\\s\\S]*android\\.intent\\.action\\.TTS_SERVICE[\\s\\S]*</queries>").containsMatchIn(manifest))
    }

    /** Log lines carry event names, counts and class names: never a transcript, partial, question or answer. */
    @Test fun noLogLineInterpolatesVoiceText() {
        val bad = sources().flatMap { f ->
            val aliases = logAliases(f.readText())
            f.readLines().filter { logsVoiceText(it, aliases) }.map { "${f.name}: ${it.trim()}" }
        }
        assertEquals(emptyList(), bad)
    }
}
