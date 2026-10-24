package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.SceneRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Scenes read the activity of a Moment from `moment_sensor` when its photos logged none (only STILL, WALKING and VEHICLE count); with no sensor rows the Scenes are exactly what they were. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneSensorActivityTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val moments = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val builder = SceneBuilder(db, enabled = { true }, clock = { 5 })
    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    @After fun close() = db.close()

    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun sid(sec: Long) = "S" + mid(sec)
    private suspend fun photo(sec: Long, motion: String = "UNKNOWN") {
        fx.frame("f$sec", t(sec) + 5)
        db.frameMetaDao().upsert(FrameMetaEntity("f$sec", null, null, motion, false, null, null, "%016x".format(0L), null, null, null, null, null, null, 1, capturedAtMs = t(sec) + 5))
        moments.build(listOf(mid(sec)))
    }
    private suspend fun sensor(sec: Long, state: String) =
        db.momentDao().replaceSensor(mid(sec), listOf(MomentSensorEntity(mid(sec), t(sec) / 1000, "trace-aaaa-0001", 1, "OFFSET_ESTIMATED", 40, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, state, null, 5, 5)), 1, "build")
    private suspend fun scenes() = db.sceneDao().allScenes().map { Triple(it.sceneId, it.boundaryRule, it.activity) }
    private suspend fun rebuild() = builder.rebuildWindow(t(0), t(3599))

    @Test fun noSensorRowsMeansExactlyTheScenesOfBefore() = runBlocking<Unit> {
        photo(0, "STILL"); photo(1, "STILL"); photo(2, "STILL"); photo(3, "WALKING"); photo(10); photo(11)
        rebuild()
        // golden (the same before this feature): frame motion cuts at 3; the two photos with no activity join the Scene before them (only one side logged one, so the frame rule decides: no change)
        assertEquals(listOf(Triple(sid(0), SceneRule.ACTIVITY_CHANGE, "STILL"), Triple(sid(3), SceneRule.HOUR_END, "WALKING")), scenes())
    }

    @Test fun anActivityFromTheSensorCutsWhereFramesLoggedNone() = runBlocking<Unit> {
        (0L..3L).forEach { photo(it) }
        rebuild(); assertEquals(listOf(Triple(sid(0), SceneRule.HOUR_END, null)), scenes())
        (0L..2L).forEach { sensor(it, "STILL") }; sensor(3, "WALKING")
        rebuild()
        assertEquals(listOf(Triple(sid(0), SceneRule.ACTIVITY_CHANGE, "STILL"), Triple(sid(3), SceneRule.HOUR_END, "WALKING")), scenes())
    }

    @Test fun aHeadMovingSecondIsNotALoggedActivity() = runBlocking<Unit> {
        photo(0); photo(1); photo(2)
        sensor(0, "STILL"); sensor(1, "HEAD_MOVING"); sensor(2, "STILL")
        rebuild()
        assertEquals("the middle second logs no activity, so the frame rule (no change) keeps one Scene", listOf(Triple(sid(0), SceneRule.HOUR_END, "STILL")), scenes())
    }

    @Test fun vehicleCountsAsAnActivity() = runBlocking<Unit> {
        photo(0); photo(1); sensor(0, "STILL"); sensor(1, "VEHICLE")
        rebuild()
        assertEquals(listOf(Triple(sid(0), SceneRule.ACTIVITY_CHANGE, "STILL"), Triple(sid(1), SceneRule.HOUR_END, "VEHICLE")), scenes())
    }

    @Test fun anActivityLoggedWithThePhotosWinsOverTheSensor() = runBlocking<Unit> {
        photo(0, "WALKING"); photo(1, "WALKING")
        sensor(0, "STILL"); sensor(1, "STILL")
        rebuild()
        assertEquals(listOf(Triple(sid(0), SceneRule.HOUR_END, "WALKING")), scenes())
    }

    @Test fun anUnknownSensorStateIsNotAnActivity() = runBlocking<Unit> {
        photo(0); photo(1); sensor(0, "UNKNOWN"); sensor(1, "SOMETHING")
        rebuild()
        assertNull(scenes().single().third)
    }
}
