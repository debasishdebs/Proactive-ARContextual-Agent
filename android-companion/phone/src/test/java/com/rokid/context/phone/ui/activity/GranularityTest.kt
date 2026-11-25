package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
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
import com.rokid.context.phone.ui.list.ListKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Activity page's level (Photos, Moments, Scenes): the mapping, the remembered choice with its fail-safe default, the Scenes count, and what the page opens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GranularityTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ViewModel>()

    @Before fun setUp() {
        graph = TestGraph.fresh(ctx)
        ctx.getSharedPreferences(PrefsGranularity.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(g: GranularityStore) = ActivityViewModel(
        Period.TODAY, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, graph.marks,
        clock = flowOf(Fixtures.at(TODAY, 20)), storagePct = flowOf(null), zone = { ZONE }, granularity = g, scenes = graph.scenes,
    ).also { vms += it }

    @Test fun eachLevelMapsToItsListAndItsWords() {
        assertEquals(listOf(ListKind.FRAMES, ListKind.MOMENTS, ListKind.SCENES), Granularity.entries.map { it.list })
        assertEquals(listOf("See all photos", "See all moments", "See all scenes"), Granularity.entries.map { it.seeAll })
        assertEquals(Granularity.MOMENTS, Granularity.DEFAULT); assertTrue(Granularity.MOMENTS.isDefault); assertFalse(Granularity.SCENES.isDefault)
        val t = Tiles(frames = 5, memories = 3, answers = 1, nudges = 0, scenes = 2)
        assertEquals(listOf(5, 3, 2), Granularity.entries.map { it.count(t) })
        assertEquals("a tile built the old way has no scenes", 0, Tiles(1, 2, 3, 4).scenes)
    }

    @Test fun anUnknownOrMissingChoiceIsTheDefault() {
        assertEquals(Granularity.DEFAULT, Granularity.parse(null)); assertEquals(Granularity.DEFAULT, Granularity.parse("")); assertEquals(Granularity.DEFAULT, Granularity.parse("YEARS"))
        assertEquals(Granularity.SCENES, Granularity.parse("SCENES"))
        ctx.getSharedPreferences(PrefsGranularity.PREFS, Context.MODE_PRIVATE).edit().putString(PrefsGranularity.KEY, "SIDEWAYS").commit()
        assertEquals(Granularity.DEFAULT, PrefsGranularity(ctx).level.value)
    }

    @Test fun theChoiceIsRememberedInTheRestorableHubSettingsFile() {
        assertEquals("hub_settings", PrefsGranularity.PREFS)
        assertEquals(Granularity.DEFAULT, PrefsGranularity(ctx).level.value)
        PrefsGranularity(ctx).set(Granularity.SCENES)
        assertEquals(Granularity.SCENES, PrefsGranularity(ctx).level.value)
    }

    @Test fun theViewModelFollowsTheStoreAndCountsScenes() = runBlocking<Unit> {
        val t = Fixtures.at(TODAY, 9)
        for (i in 1..3) graph.db.sceneDao().replace(SceneEntity("s$i", 1, t, t + i * 1000L, t + i * 1000L + 500, SceneRule.HOUR_END, membershipRev = 1, builtAt = t), listOf(ScenePartEntity("s$i", ScenePartKind.MOMENT, "0000000a", "r", t)))
        graph.db.sceneDao().replace(SceneEntity("yesterday", 1, t, Fixtures.at(TODAY.minusDays(1), 9), Fixtures.at(TODAY.minusDays(1), 9) + 500, SceneRule.HOUR_END, membershipRev = 1, builtAt = t), emptyList())
        val store = InMemoryGranularity()
        val model = vm(store)
        assertEquals(Granularity.DEFAULT, withTimeout(5_000) { model.state.first { it.today == TODAY && it.tiles.scenes == 3 } }.granularity)
        model.selectGranularity(Granularity.SCENES)
        val s = withTimeout(5_000) { model.state.first { it.granularity == Granularity.SCENES } }
        assertEquals(3, s.tiles.scenes); assertEquals(Granularity.SCENES, store.level.value)
    }

    private val lists = mutableListOf<Pair<ListKind, Period>>()
    private val picked = mutableListOf<Granularity>()
    private fun show(state: ActivityUiState) = compose.setContent {
        PcTheme { ActivityScreen(state, onSelectPeriod = {}, onTogglePause = {}, onBannerAction = {}, onPreview = {}, onOpenList = { k, p -> lists += k to p }, onOpenDay = {}, onSelectGranularity = { picked += it }) }
    }
    private val tiles = Tiles(4, 3, 0, 0, scenes = 2)

    @Test fun theDefaultPageHasTheSelectorAndNoExtraCard() {
        show(ActivityUiState(header = "Mon 12 Oct", tiles = tiles, today = TODAY))
        compose.onNodeWithTag("granularity").assertIsDisplayed()
        compose.onNodeWithText("Photos").performClick(); compose.onNodeWithText("Scenes").performClick()
        assertEquals(listOf(Granularity.PHOTOS, Granularity.SCENES), picked)
        assertEquals(0, compose.onAllNodesWithTagCount("level_card"))
    }

    @Test fun aScenesLevelShowsItsCardAndSeeAllOpensTheScenesList() {
        show(ActivityUiState(period = Period.WEEK, header = "Week of 12 Oct", tiles = tiles, today = TODAY, granularity = Granularity.SCENES))
        compose.onNodeWithTag("level_card").performClick()
        compose.onNodeWithTag("activity_list").performScrollToNode(androidx.compose.ui.test.hasTestTag("level_all"))
        compose.onNodeWithTag("level_all").performClick()
        assertEquals(listOf(ListKind.SCENES to Period.WEEK, ListKind.SCENES to Period.WEEK), lists)
    }

    @Test fun aPhotosLevelOpensTheFramesListAndTheDefaultKeepsMoments() {
        show(ActivityUiState(header = "Mon 12 Oct", tiles = tiles, today = TODAY, granularity = Granularity.PHOTOS))
        compose.onNodeWithTag("level_card").performClick()
        assertEquals(listOf(ListKind.FRAMES to Period.TODAY), lists)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
