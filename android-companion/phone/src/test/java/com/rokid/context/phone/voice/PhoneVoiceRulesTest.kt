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

    private val voiceText = Regex("\\$\\{?(text|partial|question|transcript|answer|pcm|spoken)\\b|\\$\\{[^\\}]*\\b(text|partial|question|transcript|answer|pcm|spoken)\\b")

    private fun badLogLines(f: File) = f.readLines().filter { l -> "Log." in l && voiceText.containsMatchIn(l) }.map { "${f.name}: ${it.trim()}" }

    @Test fun theScannerCatchesBothForms() {
        val tmp = File.createTempFile("rule", ".kt").apply { deleteOnExit() }
        tmp.writeText("Log.i(TAG, \"q=\$question\")\nLog.i(TAG, \"a=\${r.answer}\")\nLog.i(TAG, \"n=\${text.length}\")\nLog.i(TAG, \"listen start\")\n")
        assertEquals(3, badLogLines(tmp).size)
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
        assertTrue("the hook hands the resume to voice", "VoiceGraph.get(activity).onForeground()" in hook)
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
        assertTrue(Regex("<queries>[\\s\\S]*android\\.intent\\.action\\.TTS_SERVICE[\\s\\S]*</queries>").containsMatchIn(merged))
        assertTrue("android.permission.RECORD_AUDIO" in merged)
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in merged)
        val service = Regex("<service\\b[^>]*>").findAll(merged).map { it.value }.single { "ContextHubService" in it }
        assertTrue(service, "android:foregroundServiceType=\"connectedDevice|microphone\"" in service)
    }
}
