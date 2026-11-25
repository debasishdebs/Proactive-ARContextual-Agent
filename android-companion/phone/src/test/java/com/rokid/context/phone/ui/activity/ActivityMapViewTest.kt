package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MomentLocationEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Activity List / Map toggle (remembered like the level), the read-only source behind the map (range, Scenes, Forget) and the map's empty and no-key states. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityMapViewTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val src get() = AppMomentMapSource(ctx)
    private val vms = mutableListOf<ViewModel>()

    @Before fun setUp() {
        graph = TestGraph.fresh(ctx)
        ctx.getSharedPreferences(PrefsViewMode.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private suspend fun loc(id: String, at: Long, lat: Double = 17.38, lon: Double = 78.48) =
        graph.db.momentLocationDao().insertIfAbsent(MomentLocationEntity(id, at, "FRAME", "f$id", lat, lon, 10.0, 0, "ZONE_STREAM", null, null, at))

    @Test fun theModeIsRememberedInTheRestorableHubSettingsFileAndDefaultsToList() {
        assertEquals("hub_settings", PrefsViewMode.PREFS)
        assertEquals(ActivityViewMode.LIST, PrefsViewMode(ctx).mode.value)
        PrefsViewMode(ctx).set(ActivityViewMode.MAP)
        assertEquals(ActivityViewMode.MAP, PrefsViewMode(ctx).mode.value)
        ctx.getSharedPreferences(PrefsViewMode.PREFS, Context.MODE_PRIVATE).edit().putString(PrefsViewMode.KEY, "SIDEWAYS").commit()
        assertEquals(ActivityViewMode.LIST, PrefsViewMode(ctx).mode.value)
    }

    @Test fun theViewModelFollowsTheStore() = runBlocking<Unit> {
        val store = InMemoryViewMode()
        val model = ActivityViewModel(
            Period.TODAY, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, graph.marks,
            clock = flowOf(Fixtures.at(TODAY, 20)), storagePct = flowOf(null), zone = { ZONE }, viewMode = store, scenes = graph.scenes,
        ).also { vms += it }
        assertEquals(ActivityViewMode.LIST, withTimeout(5_000) { model.state.first { it.today == TODAY } }.viewMode)
        model.selectViewMode(ActivityViewMode.MAP)
        assertEquals(ActivityViewMode.MAP, withTimeout(5_000) { model.state.first { it.viewMode == ActivityViewMode.MAP } }.viewMode)
        assertEquals(ActivityViewMode.MAP, store.mode.value)
    }

    @Test fun theSourceReadsOnlyLocationsInTheRangeAndAForgetVanishesThemOnTheNextRead() = runBlocking<Unit> {
        loc("a", 100); loc("b", 200); loc("c", 300)
        assertEquals(listOf("b"), src.moments(150L..250L).map { it.momentId })
        graph.db.momentLocationDao().deleteAllMomentLocations()
        assertTrue(src.moments(0L..1000L).isEmpty())
    }

    @Test fun scenesCountOnceAtTheirFirstLocatedMoment() = runBlocking<Unit> {
        val t = Fixtures.at(TODAY, 9)
        loc("m1", t + 1, 1.0, 1.0); loc("m2", t + 2, 2.0, 2.0)
        graph.db.sceneDao().replace(SceneEntity("s1", 1, t, t, t + 500, SceneRule.HOUR_END, membershipRev = 1, builtAt = t), listOf(
            ScenePartEntity("s1", ScenePartKind.MOMENT, "m0", "r", t), ScenePartEntity("s1", ScenePartKind.MOMENT, "m1", "r", t + 1), ScenePartEntity("s1", ScenePartKind.MOMENT, "m2", "r", t + 2)))
        val rows = src.scenes(t..t + 1000)
        val pts = MomentHeat.points(Granularity.SCENES, emptyList(), rows, null)
        assertEquals("m0 has no location, so the Scene sits at m1", listOf("m1"), pts.map { it.momentId })
        assertTrue(src.scenes(t - 5_000..t - 1).isEmpty())
    }

    private fun show(mode: ActivityViewMode, map: ActivityMapState, picks: MutableList<ActivityViewMode> = mutableListOf()) = compose.setContent {
        PcTheme {
            ActivityScreen(
                ActivityUiState(today = TODAY, viewMode = mode), {}, {}, {}, {}, { _, _ -> }, {},
                onSelectViewMode = { picks += it },
                mapContent = { ActivityHeatMap(map, Granularity.MOMENTS, null, null, ZONE) {} },
            )
        }
    }

    @Test fun theListIsTheDefaultAndTheToggleAsksForTheMap() {
        val picks = mutableListOf<ActivityViewMode>()
        show(ActivityViewMode.LIST, ActivityMapState(), picks)
        compose.onNodeWithTag("view_mode").assertIsDisplayed()
        compose.onNodeWithText("Map").performClick()
        assertEquals(listOf(ActivityViewMode.MAP), picks)
        compose.onNodeWithTag("activity_map").assertDoesNotExist()
    }

    @Test fun anEmptyMapSaysHowToTurnLocationsOn() {
        show(ActivityViewMode.MAP, ActivityMapState(loaded = true))
        compose.onNodeWithTag("map_empty").assertExists()
        compose.onNodeWithText(MomentHeat.EMPTY).assertExists()
    }

    @Test fun withoutAMapKeyTheMapShowsTheGridAndTheAddAKeyNotice() {
        show(ActivityViewMode.MAP, ActivityMapState(listOf(HeatPoint("a", 17.38, 78.48, 1)), loaded = true))
        compose.onNodeWithTag("activity_map").assertExists()
        compose.onNodeWithTag("activity_map_notice").assertExists()
        compose.onNodeWithText("Add a map key in Settings", substring = true).assertExists()
    }
}
