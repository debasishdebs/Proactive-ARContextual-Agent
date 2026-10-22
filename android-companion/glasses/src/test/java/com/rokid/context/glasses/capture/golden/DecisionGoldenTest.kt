package com.rokid.context.glasses.capture.golden

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * Frozen decisions of the unmodified pre-slice-3a capture code (settings mode). The files under `src/test/resources/golden`
 * were recorded once from `s2/int`; the move-only refactor (Task 2) must reproduce them unchanged. To (re)record deliberately,
 * run with `GOLDEN_RECORD=1` (never as part of a refactor).
 */
class DecisionGoldenTest {
    private fun fileName(name: String) = "decisions-$name.txt"

    private fun run(s: GoldenScript): String {
        var text = ""
        runTest(timeout = 5.minutes) { text = DecisionGoldenRecorder.record(this, s) }
        return text
    }

    @Test fun goldensReproduce() {
        val record = System.getenv("GOLDEN_RECORD") == "1"
        val recorded = ArrayList<String>()
        for (s in GoldenScripts.all) {
            val actual = run(s)
            if (record) {
                val dir = File("src/test/resources/golden").also { it.mkdirs() }
                File(dir, fileName(s.name)).writeText(actual)
                recorded += s.name
                continue
            }
            val stream = javaClass.getResourceAsStream("/golden/${fileName(s.name)}")
            assertTrue(stream != null, "missing golden ${fileName(s.name)}")
            val expected = stream.bufferedReader().use { it.readText() }
            assertEquals(expected, actual, "golden mismatch for ${s.name}")
        }
        // A recording run must never be green: it is a deliberate act that the next, unset run must confirm.
        if (record) fail("GOLDEN_RECORD=1: recorded ${recorded.size} goldens; rerun without GOLDEN_RECORD to verify")
    }

    @Test fun recordingIsDeterministic() {
        for (s in listOf(GoldenScripts.unknownWalk, GoldenScripts.bindBackoff)) assertEquals(run(s), run(s), s.name)
    }

    @Test fun goldensCoverTheDecisionKinds() {
        val all = GoldenScripts.all.joinToString("\n") { s ->
            javaClass.getResourceAsStream("/golden/${fileName(s.name)}")!!.bufferedReader().use { it.readText() }
        }
        for (k in listOf(" open timer ", " open dwell ", " keep ", " skip same ", " glance ", " gap ", " close ", " now ", " heat SLOW", " rest start", " open timer failed")) {
            assertTrue(all.contains(k), "no '$k' line in any golden")
        }
    }
}
