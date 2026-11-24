package com.rokid.context.work.frames.place.replay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Runs the replay on the user's REAL bundle. Skipped automatically unless REPLAY_BUNDLE (or -Dreplay.bundle) names a bundle folder, so CI stays green. Writes only outside the repo:
 *   REPLAY_BUNDLE=~/Desktop/rokid/backups/replay-bundle-2026-11-24 REPLAY_OUT=~/Desktop/rokid/backups/replay-out ./gradlew ... --tests '*TripReplayRealDataTest*'
 * The report states in `summary.md` the outcome per place; the test itself prints counts only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TripReplayRealDataTest {
    @Test fun replayTrip_writesTimelinesAndVerdicts() {
        val dir = ReplayEnv.bundleDir()
        assumeTrue("no real bundle (REPLAY_BUNDLE not set): skipped", dir != null)
        assertFalse("the bundle must not sit inside the repo", ReplayEnv.insideGitTree(dir!!))
        val out = File(ReplayEnv.outDir(ReplayEnv.configuredOut()), "run-" + LocalDateTime.now().toString().replace(':', '-').substringBefore('.'))
        val bundle = ReplayBundle.load(dir)
        var groups: List<ReplayGroup> = emptyList(); var cal: Calibration? = null
        runTest(timeout = kotlin.time.Duration.parse("30m")) { ReplayReport.defaultGroups(bundle, this).let { groups = it.first; cal = it.second } }
        ReplayReport.write(out, bundle, groups, cal)
        val bad = ReplayReport.productionViolations(bundle, groups)
        println("REPLAY runs=${groups.size + groups.sumOf { it.overlays.size }} frames=${bundle.frames.size} spans=${bundle.truth.size} legacyCalibration=${cal?.let { "${it.reproduced}/${it.checked} trusted=${it.trusted}" }} productionViolations=${bad.size}")
        assertTrue(File(out, "summary.md").isFile)
        assertTrue("production-flag acceptance failed: $bad", bad.isEmpty()) // P3-9: asserted against the shipped flags, apart from the legacy calibration
    }
}

/** Always runs: the output folder guard of R1. */
class ReplayOutputGuardTest {
    @Test fun refusesOutputInsideRepo() {
        val here = File(".").absoluteFile.canonicalFile
        assertTrue(ReplayEnv.insideGitTree(here))
        var refused = false
        try { ReplayEnv.outDir(File(here, "replay-out").path) } catch (_: IllegalArgumentException) { refused = true }
        assertTrue(refused)
        var noPath = false
        try { ReplayEnv.outDir(null) } catch (_: IllegalArgumentException) { noPath = true }
        assertTrue(noPath)
        val outside = File(System.getProperty("java.io.tmpdir"), "replay-out-guard-" + System.nanoTime())
        assertEquals(outside.absoluteFile, ReplayEnv.outDir(outside.path).absoluteFile)
    }

    @Test fun skippedWithoutABundle() {
        // No bundle configured in CI: the real-data test assumes false and is reported skipped, not failed.
        if (System.getenv("REPLAY_BUNDLE") == null && System.getProperty("replay.bundle") == null) assertEquals(null, ReplayEnv.bundleDir())
    }
}
