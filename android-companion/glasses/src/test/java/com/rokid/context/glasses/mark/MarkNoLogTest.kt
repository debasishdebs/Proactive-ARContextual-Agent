package com.rokid.context.glasses.mark

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The glasses never log a mark: the diagnostic files survive the epoch wipe, so nothing about a mark (not even its time or listen flag) may be written to them. */
class MarkNoLogTest {
    private val dir: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/rokid/context/glasses/mark") }.first { it.isDirectory }

    @Test fun theMarkPackageNeverLogs() {
        val bad = dir.listFiles()!!.filter { it.extension == "kt" && Regex("""\\b(Log|DiagLog)\\.""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue(bad.isEmpty(), "mark code must not log: $bad")
    }

    @Test fun theMarkMessageCarriesNoIntentOrTranscriptField() {
        val fields = com.rokid.context.core.model.MarkMessage::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") && it != "Companion" }
        assertTrue(fields.none { it.contains("intent", true) || it.contains("text", true) || it.contains("transcript", true) }, "fields: $fields")
    }
}
