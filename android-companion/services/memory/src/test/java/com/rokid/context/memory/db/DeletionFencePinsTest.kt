package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PIN (deferred, no production writer yet): calibration and helper-job inserts are not epoch-fenced. When 3b adds their writers they must be fenced; flip this test then. */
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
}
