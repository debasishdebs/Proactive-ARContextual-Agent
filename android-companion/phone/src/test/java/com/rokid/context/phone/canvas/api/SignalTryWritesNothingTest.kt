package com.rokid.context.phone.canvas.api

import android.app.Application
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.signal.SignalTryReader
import com.rokid.context.canvas.signal.ZoneStatus
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.phone.canvas.DeveloperModeStore
import com.rokid.context.phone.canvas.server.HttpRequest
import com.rokid.context.phone.ui.settings.capture.SignalConfigStore
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v1.2 row 2: a try persists NOTHING (config, prefs, every table), cannot reach a model, hub or plan publisher, and the default capture plan hash is untouched. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SignalTryWritesNothingTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = DatabaseFactory.inMemory(app)
    private val base = 1_761_000_000L
    private val epoch = DeletionEpoch("install-a", 3, 1_000)
    private val prefs = app.getSharedPreferences("sigtry-nothing", 0).also { it.edit().clear().commit() }
    private val dev = DeveloperModeStore.get(app)
    @After fun close() = db.close()

    private val def = """{"steps":[{"name":"w","inputs":[],"nodes":[{"id":"w","op":"win","agg":"mean","sec":10,"name":"stepHz"},{"id":"k","op":"const","value":1.2},{"id":"c","op":"cmp","f":">"}],"wires":[["w","c.a"],["k","c.b"]],"out":"c"}],"out":"w"}"""

    private fun tables(): Map<String, Int> {
        val names = ArrayList<String>()
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name")).use { c -> while (c.moveToNext()) names += c.getString(0) }
        return names.associateWith { n -> db.openHelper.readableDatabase.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM `$n`")).use { c -> c.moveToFirst(); c.getInt(0) } }
    }

    @Test fun aTryChangesNoPrefNoConfigNoTableAndNotThePlanHash() = runBlocking<Unit> {
        val md = db.momentDao()
        db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 5, false, 1, null, epoch.installId, epoch.counter, true, base * 1000, (base + 700) * 1000, 1, base * 1000, (base + 700) * 1000, TraceClock.OFFSET_ESTIMATED, 40), DaoFence(5, epoch, { 5 }, { epoch }))
        for (i in 0 until 700) {
            md.replace(MomentEntity(MomentId.format(base + i), 1, 1, "h", 1, MomentType.FRAME), emptyList())
            md.replaceSensor(MomentId.format(base + i), listOf(MomentSensorEntity(MomentId.format(base + i), base + i, "t1", 1, TraceClock.OFFSET_ESTIMATED, 40, 1.0, 0.5, 0.6, 0.25, 0.125, 1.5, 0.75, "WALKING", "REST", 50, 40)), 1, "build")
        }
        val store = SignalConfigStore(prefs)
        store.saveDerived("walky", kotlinx.serialization.json.Json.parseToJsonElement(def))
        val devPrefs = app.getSharedPreferences(DeveloperModeStore.PREFS, 0)
        dev.set(true); dev.setSignalTry(true)
        val tablesBefore = tables(); val prefsBefore = prefs.all.toMap(); val devBefore = devPrefs.all.toMap(); val cfgBefore = store.config.value
        val hashBefore = PlanJson.configHash(PlanResolver.identity())
        assertEquals("f5782e0d8610", hashBefore)

        val reader = SignalTryReader(db, DataOpGate(leases = Leases()), ProvenanceGate(db), { ZoneStatus.CLEAR }, { 5L }, { base + 800 })
        val route = SignalRoutes(store, SignalTry(reader, { store.config.value }, dev.enabled, dev.signalTryEnabled))
        val r = route.handle(HttpRequest("POST", "/api/signals/try", emptyMap(), emptyMap(), """{"graph":$def,"name":"walky","seconds":600}""".toByteArray()))!!
        assertEquals(String(r.body), 200, r.status); assertTrue(String(r.body).contains("\"available\":true"))

        assertEquals(tablesBefore, tables()); assertEquals(prefsBefore, prefs.all.toMap()); assertEquals(devBefore, devPrefs.all.toMap())
        assertEquals(cfgBefore, store.config.value); assertEquals(1, store.config.value.derived.size)
        assertEquals(hashBefore, PlanJson.configHash(PlanResolver.identity()))
        dev.set(false); dev.setSignalTry(false)
    }

    @Test fun theFlagIsOffByDefaultAndTheRouteCannotReachAModelHubOrPlanPublisher() {
        assertFalse("default OFF", app.getSharedPreferences("fresh-dev", 0).getBoolean(DeveloperModeStore.KEY_SIGNAL_TRY, false))
        val types = (SignalTry::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() } + SignalTryReader::class.java.declaredConstructors.flatMap { it.parameterTypes.toList() }).map { it.name }
        assertTrue(types.joinToString(), types.none { n -> listOf("LanguageModel", "Hub", "Router", "Publisher", "Sender", "Transport", "Http").any { n.contains(it) } })
    }
}
