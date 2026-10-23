package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.helper.HelperProtocol
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.core.model.PROTOCOL_VERSION
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * WP9 privacy invariants of the whole 3b change:
 *  - no `android.util.Log` in any 3b file (the always-on ratchet `RawLogScanTest` (LGM1) has not landed, so this test keeps the grep over the exact 3b file list, no exceptions;
 *    when LGM1 lands it is the one scan and this test's scan is deleted),
 *  - no id, token, hash, code or payload text in any log line of a whole run through the production chain,
 *  - `PROTOCOL_VERSION == 1` (the glasses link),
 *  - the sensing classes, `HudSettings` and `pc_trace_ctl` are untouched and 3b never reaches them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivacyInvariantsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val lines = mutableListOf<Line>()
    private val sink = object : LogSink { override fun emit(line: Line, route: Route) { synchronized(lines) { lines += line } } }

    @Before fun install() { PcLog.testOnlyResetNeverCallFromProduction(); PcLog.install(sink); PcLog.setDiagnostic(true) }
    @After fun reset() { PcLog.testOnlyResetNeverCallFromProduction() }

    private val root = File("..") // the Gradle test working directory is android-companion/phone
    private fun src(rel: String) = File(root, rel)

    // ---- the 3b file list ----

    private val phoneCanvas = "phone/src/main/java/com/rokid/context/phone/canvas"
    private val orch = "services/orchestration/src/main/java/com/rokid/context"
    private val memory = "services/memory/src/main/java/com/rokid/context/memory"

    /** Every file 3b created, plus the files 3b edited whose logging 3b owns (section 12: migrated by the WP that edits them). Whole directories where 3b owns the directory. */
    private fun threeBFiles(): List<File> {
        val dirs = listOf("$phoneCanvas/helper", "$orch/canvas/helper", "$memory/canvas")
        val named = listOf(
            "$phoneCanvas/server/HelperAuth.kt", "$phoneCanvas/server/PairingLimiter.kt", "$phoneCanvas/server/CanvasRouter.kt", "$phoneCanvas/server/CanvasServer.kt",
            "$phoneCanvas/server/CanvasAuth.kt", "$phoneCanvas/CanvasGraph.kt", "$phoneCanvas/CanvasService.kt", "$phoneCanvas/api/CanvasBackendImpl.kt",
            "$phoneCanvas/ui/HelperCards.kt", "$phoneCanvas/ui/CanvasHome.kt",
            "$orch/canvas/PythonExecutor.kt", "$orch/canvas/HelperExecutor.kt", "$orch/canvas/ExperimentExecutors.kt", "$orch/canvas/CanvasRunner.kt", "$orch/canvas/PipelineInterpreter.kt",
            "$memory/db/CanvasSlice3Dao.kt", "$memory/db/CanvasSlice3Entities.kt", "$memory/forget/DeletionGraph.kt",
            "services/ai/src/main/java/com/rokid/context/ai/canvas/NodeTypes.kt", "services/ai/src/main/java/com/rokid/context/ai/canvas/PipelineValidator.kt",
        ).map { src(it) }
        val all = dirs.flatMap { d -> src(d).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() } + named
        return all.distinct()
    }

    /** Code without comments (a comment may mention the platform logger; a call may not). */
    private fun code(f: File): String = f.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }
    private val rawLog = Regex("""android\.util\.Log|\bLog\.(v|d|i|w|e|wtf)\(|\bprintln\(|System\.(out|err)\.|printStackTrace\(""")

    @Test fun noAndroidUtilLogInAny3bFile() {
        val files = threeBFiles()
        assertTrue("the 3b file list is real: ${files.size}", files.size > 40 && files.all { it.isFile })
        val offenders = files.filter { rawLog.containsMatchIn(code(it)) }.map { it.name }
        assertEquals("raw platform logging in 3b files", emptyList<String>(), offenders)
    }

    /**
     * Two files 3b edited (the S6 guard in `CanvasPublisher`, a grants-prefs constant in `MemoryWipe`) already held platform log calls BEFORE 3b and 3b added none. They are
     * not migrated here (decision needed, see the WP9 report); until the always-on ratchet moves them, the count may only go down.
     */
    @Test fun preexistingPlatformLogCallsInTwoEditedFilesDoNotGrow() {
        val budget = mapOf("$orch/canvas/CanvasPublisher.kt" to 5, "$orch/orchestration/MemoryWipe.kt" to 5)
        for ((path, max) in budget) {
            val n = Regex("""android\.util\.Log|\bLog\.(v|d|i|w|e)\(""").findAll(code(src(path))).count()
            assertTrue("$path has $n platform log references, at most $max allowed", n <= max)
        }
    }

    // ---- no ids or tokens in log lines ----

    @Test fun noIdsOrTokensInAnyLogLineOfAWholeRun() {
        val k = BrowserRunKit(ApplicationProvider.getApplicationContext(), tmp.root)
        try {
            k.seedFrame("frame-q7z1", 1_000); k.seedFrame("frame-w3x9", 2_000)
            val pipeline = k.savePythonPipeline()
            val secrets = mutableListOf(k.browserToken, k.helperToken, "frame-q7z1", "frame-w3x9", pipeline, "def run", "Label the scene", "desk")
            // run 1: tapped and completed
            val r1 = k.startRun(pipeline, listOf("frame-q7z1", "frame-w3x9")); secrets += r1
            val j1 = k.awaitPending(); secrets += j1
            val info1 = runBlocking { k.h.backend.service.pendingConfirmations().single() }
            secrets += info1.bundleHash
            secrets += k.h.backend.confirm.tag(com.rokid.context.memory.canvas.ConfirmBinding(j1, info1.bundleHash, info1.sessionGen, info1.expiresAtMs))
            k.tap(j1); k.helperCompletes(items = 2)
            k.awaitStatus(r1, "DONE")
            // run 2: declined; run 3: expired; run 4: Forget; plus refused requests on the way
            val r2 = k.startRun(pipeline, listOf("frame-q7z1")); secrets += r2
            val j2 = k.awaitPending(); secrets += j2
            k.h.backend.adapter.onCancel(j2); runBlocking { k.h.backend.adapter.awaitIdle() }; k.awaitStatus(r2, "DONE", "CANCELLED")
            val r3 = k.startRun(pipeline, listOf("frame-q7z1")); secrets += r3
            secrets += k.awaitPending()
            k.h.now += HelperProtocol.CONFIRM_TTL_MS; k.awaitStatus(r3, "DONE", "CANCELLED")
            val r4 = k.startRun(pipeline, listOf("frame-w3x9")); secrets += r4
            secrets += k.awaitPending()
            k.forget("frame-w3x9"); k.awaitStatus(r4, "STOPPED_FORGET", "DONE", "CANCELLED")
            k.call("GET", "/api/runs/$r1", "wrong-token-value"); k.helperPayload(j1, 99); k.call("POST", "/api/helper/poll", k.browserToken)
            secrets += "wrong-token-value"

            val all = synchronized(lines) { lines.map { it.tag + " " + it.msg } } + k.routerLines
            assertTrue("lines were written: ${all.size}", all.size > 20)
            for (line in all) {
                for (s in secrets) assertFalse("'$line' carries a secret (${s.take(6)}...)", line.contains(s))
                assertFalse("'$line' holds a hash-like or token-like run of characters", Regex("[0-9a-fA-F]{16,}").containsMatchIn(line))
                assertFalse("'$line' holds a source or payload fragment", line.contains("return {") || line.contains("\"n\"") || line.contains("outSchema"))
            }
            for (line in k.routerLines) assertTrue("'$line' is not method + route template + status + ms", Regex("^[A-Z]+ /[A-Za-z0-9/:_-]* [0-9]{3} [0-9]+ms$").matches(line))
        } finally { k.close() }
    }

    // ---- the glasses link and the sensing side ----

    @Test fun protocolVersionIsOne() {
        assertEquals(1, PROTOCOL_VERSION)
        assertTrue(File(root, "core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt").readText().contains("const val PROTOCOL_VERSION = 1"))
        assertEquals("the helper protocol is its own number", 1, HelperProtocol.HELPER_PROTOCOL)
    }

    @Test fun sensingClassesAreUntouched() {
        val pins = mapOf(
            "core/capture/src/main/kotlin/com/rokid/context/glasses/capture/motion/FeatureLogger.kt" to "e4d202b05ce8299e4c7f16b4692ea624a18df20362e0db343c151f59b84e538f",
            "core/capture/src/main/kotlin/com/rokid/context/glasses/capture/motion/GlanceDetector.kt" to "d58795770133290b97033497a523b1000b7ca26cc8e5b33eccc1836bbd1671f8",
            "core/capture/src/main/kotlin/com/rokid/context/glasses/capture/motion/GlanceParams.kt" to "3ae12d265514e1675ba575f40858d3f171d9700c3d6e7a54aaa84f68d23f0360",
            "core/capture/src/main/kotlin/com/rokid/context/glasses/capture/motion/MotionClassifier.kt" to "8c1cc34e14eeaac3f048ef500195448a468aca6dac13ee8906bc6a1a924c744f",
            "core/capture/src/main/kotlin/com/rokid/context/glasses/capture/motion/SampleTap.kt" to "b7cdcb4987e0ac9cf2fddc099a247328938aadcfe07b955c61a61b3e2b1fed46",
            "glasses/src/main/java/com/rokid/context/glasses/capture/motion/MotionCsv.kt" to "6927d95c260e4f259fed96e33692b3ef29ff7128313da2cef999c03928c7fb3b",
            "glasses/src/main/java/com/rokid/context/glasses/capture/motion/MotionHintSender.kt" to "3626ca71a1d974af896ea44d8f312f9c738151f4c3a03a68101bf6b4fba7dc52",
            "glasses/src/main/java/com/rokid/context/glasses/capture/motion/SensorFeed.kt" to "6f45fff96d9f70f70aaca8c466bca2c3264b81bed0ac1f07b68479d2737c274f",
            "glasses/src/main/java/com/rokid/context/glasses/capture/motion/WearDetector.kt" to "eabc666457ddfcade389d168c32e0dd16ffd3de4e0632cb5a061702b9e7a8f3c",
            "glasses/src/main/java/com/rokid/context/glasses/capture/session/SensorRatePolicy.kt" to "bb43a2a91866b7c4b31a5c37ce6d7570e1ac6132c55b7255899487e61a2974b1",
        )
        for ((path, sha) in pins) {
            val f = src(path)
            assertTrue("$path exists", f.isFile)
            val actual = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("$path changed: a sensing class must not be touched by 3b (update the pin only with a deliberate sensing change)", sha, actual)
        }
        // and the 3b code does not even name them, nor the trace-control channel or the HUD settings
        val banned = listOf("MotionClassifier", "GlanceDetector", "SensorFeed", "SensorRatePolicy", "WearDetector", "SampleTap", "MotionHintSender", "pc_trace_ctl", "HudSettings")
        for (f in threeBFiles()) for (b in banned) assertFalse("${f.name} mentions $b", code(f).contains(b))
    }
}
