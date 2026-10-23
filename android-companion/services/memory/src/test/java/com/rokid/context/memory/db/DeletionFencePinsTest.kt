package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * PIN (deferred, no production writer yet): the calibration insert and the LEGACY helper-job insert are not epoch-fenced. The helper-job writer of 3b (WP3) is
 * `insertHelperJobFenced`; no production source may call the legacy `insertHelperJob` (scan below). Flip the calibration pin when its writer is added.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeletionFencePinsTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    @After fun close() = db.close()

    @Test fun calibrationAndHelperInsertsAreStillUnfenced() = runBlocking {
        val s = db.canvasSlice3Dao()
        s.insertCalibration(CanvasCalibrationEntity(configHash = "c", settingsRev = 1, mode = "LIVE", phasesJson = "[]", createdAt = 1))
        s.insertHelperJob(CanvasHelperJobEntity("j1", "py", "QUEUED", createdAt = 1, updatedAt = 1))
        assertEquals(1, s.calibrations("c").size); assertEquals(1, s.openHelperJobs().size)
    }

    /** Core plan WP3: production code inserts helper jobs only through the fenced insert. The scan covers every module's `src/main` (the DAO file declares the legacy method). */
    @Test fun productionCodeNeverCallsTheUnfencedHelperInsert() {
        var d: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !java.io.File(d, "settings.gradle.kts").isFile) d = d.parentFile
        val root = requireNotNull(d) { "android-companion root not found" }
        val offenders = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") && it.name != "CanvasSlice3Dao.kt" }
            .filter { Regex("""\binsertHelperJob\s*\(""").containsMatchIn(it.readText()) }.map { it.path }.toList()
        org.junit.Assert.assertTrue("unfenced insertHelperJob used in production: $offenders", offenders.isEmpty())
    }
}
