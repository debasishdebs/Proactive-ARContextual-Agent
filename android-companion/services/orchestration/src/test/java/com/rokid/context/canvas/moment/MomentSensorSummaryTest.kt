package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentSensorEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The compact sensor summary that readers get ONLY when rows exist: on a resolved Moment and, per Scene and per member Moment, from the `input.scenes` executor. It never enters a content hash. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentSensorSummaryTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val md = db.momentDao()
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val plain = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    private val withSensor = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao(), md))
    private val hour = Math.floorDiv(MS, 3_600_000L) * 3_600_000L
    @After fun close() = db.close()

    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))
    private fun row(sec: Long, gyroMean: Double, gyroMax: Double, accVar: Double, stepHz: Double, state: String = "STILL", ctx: String? = "REST") =
        MomentSensorEntity(mid(sec), t(sec) / 1000, "trace-aaaa-0001", 1, "OFFSET_ESTIMATED", 40, gyroMax, gyroMean, 0.0, 0.0, accVar, stepHz, 0.0, state, ctx, 10, 10)
    private suspend fun photo(sec: Long) { fx.frame("f$sec", t(sec) + 5); builder.build(listOf(mid(sec))) }
    private suspend fun sensor(r: MomentSensorEntity) = md.replaceSensor(r.momentId, listOf(r), 1, "build")

    @Test fun theSummaryIsTheMeanAndMaxOfAFewFeaturesAndTheMostFrequentStateAndContext() {
        val s = SensorSummary.of(listOf(row(0, 1.0, 4.0, 0.5, 1.0, "STILL", "REST"), row(1, 3.0, 2.0, 1.5, 2.0, "WALKING", "WALKING"), row(2, 5.0, 6.0, 1.0, 3.0, "WALKING", null)))!!
        assertEquals(3, s.seconds)
        assertEquals(3.0, s.gyroMean, 1e-9); assertEquals(6.0, s.gyroMax, 1e-9)
        assertEquals(1.0, s.accVarMean, 1e-9); assertEquals(1.5, s.accVarMax, 1e-9)
        assertEquals(2.0, s.stepHzMean, 1e-9); assertEquals(3.0, s.stepHzMax, 1e-9)
        assertEquals("WALKING", s.state); assertEquals("REST", s.context) // a tie of context counts goes to the smaller name
        assertNull(SensorSummary.of(emptyList()))
    }

    @Test fun aResolvedMomentCarriesTheSummaryOnlyWhenARowExists() = runBlocking<Unit> {
        photo(0); photo(1); sensor(row(0, 2.0, 5.0, 0.25, 1.5, "WALKING"))
        val m = withSensor.resolve(listOf(mid(0), mid(1))).associateBy { it.id }
        assertEquals(1, m.getValue(mid(0)).sensor!!.seconds); assertEquals(2.0, m.getValue(mid(0)).sensor!!.gyroMean, 0.0); assertEquals("WALKING", m.getValue(mid(0)).sensor!!.state)
        assertNull(m.getValue(mid(1)).sensor)
        assertNotNull(withSensor.window(t(0), t(2) - 1, 10).first { it.id == mid(0) }.sensor)
    }

    @Test fun aResolverWithoutTheSensorTableReadsNoSensor() = runBlocking<Unit> {
        photo(0); sensor(row(0, 2.0, 5.0, 0.25, 1.5))
        assertNull(plain.resolve(listOf(mid(0))).single().sensor)
    }

    @Test fun sensorRowsNeverChangeAMomentsContentHashOrBindingKey() = runBlocking<Unit> {
        photo(0)
        val before = plain.resolve(listOf(mid(0))).single()
        sensor(row(0, 2.0, 5.0, 0.25, 1.5))
        val after = withSensor.resolve(listOf(mid(0))).single()
        assertEquals(before.contentHash, after.contentHash); assertEquals(before.contentRev, after.contentRev); assertEquals(before.bindingKey, after.bindingKey)
    }

    @Test fun theScenesExecutorReturnsASceneSummaryAndTheMemberMomentSummariesOnlyWhenRowsExist() = runBlocking<Unit> {
        photo(0); photo(1); photo(2)
        SceneBuilder(db, enabled = { true }, clock = { 5 }).rebuildWindow(t(0), t(3599))
        val exec = SceneInputExecutor(db)
        val none = exec.run(t(0), t(3599)).single()
        assertNull(none.sensor); assertTrue(none.momentSensor.isEmpty())
        val json0 = exec.toJson(listOf(none)).jsonArray.single().jsonObject
        assertFalse("no sensor key at all without rows", json0.containsKey("sensor") || json0.containsKey("momentSensor"))
        sensor(row(0, 1.0, 4.0, 0.5, 1.0)); sensor(row(2, 3.0, 2.0, 1.5, 2.0))
        val v = exec.run(t(0), t(3599)).single()
        assertEquals(2, v.sensor!!.seconds); assertEquals(2.0, v.sensor!!.gyroMean, 1e-9); assertEquals(4.0, v.sensor!!.gyroMax, 1e-9)
        assertEquals(setOf(mid(0), mid(2)), v.momentSensor.keys)
        val json = exec.toJson(listOf(v)).jsonArray.single().jsonObject
        assertEquals("2", json.getValue("sensor").jsonObject.getValue("seconds").jsonPrimitive.content)
        assertEquals(setOf(mid(0), mid(2)), json.getValue("momentSensor").jsonObject.keys)
    }
}
