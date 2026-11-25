package com.rokid.context.phone.ui.list

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.Cursor
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ObjectDerivedEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.activity.Period
import com.rokid.context.phone.ui.activity.PeriodWindow
import com.rokid.context.phone.ui.preview.PreviewCtx
import com.rokid.context.phone.ui.preview.PreviewSection
import com.rokid.context.phone.ui.preview.Previewables
import com.rokid.context.phone.ui.preview.RepoActivityData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Scenes list over the real database: keyset paging with no cap, the fresh words only, the member Moments by reference, the empty state, search over the row text, and nothing about a place. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ScenesListTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() = TestGraph.close(ctx)

    private fun data() = RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }, sceneRepo = graph.scenes)
    private val range = PeriodWindow.of(Period.TODAY, TODAY).millis(Fixtures.ZONE)
    private val t0 = Fixtures.at(TODAY, 9)

    private suspend fun scene(id: String, startMs: Long, moments: List<String> = listOf("0000000a"), activity: String? = null, text: String? = null, fresh: Boolean = true) {
        graph.db.sceneDao().replace(SceneEntity(id, 1, startMs - startMs % 3_600_000, startMs, startMs + 60_000, SceneRule.CAP_5_MIN, activity = activity, membershipRev = 1, builtAt = startMs), moments.map { ScenePartEntity(id, ScenePartKind.MOMENT, it, "r", startMs) })
        val seq = graph.db.objectDao().appendAudit("SCENE", id, "BUILT", startMs, "build")
        if (text != null) graph.db.objectDao().upsertDerived(ObjectDerivedEntity("SCENE", id, "SUMMARY", text, null, if (fresh) seq else seq - 1, null, startMs))
    }

    private suspend fun all(): List<String> {
        val d = data(); val out = mutableListOf<String>(); var after = Cursor.head(range)
        while (true) { val p = d.page(ListKind.SCENES, range, after, 100); out += p.ids; if (p.ids.size < 100) return out; after = p.cursors.last() }
    }

    @Test fun anEmptyPeriodHasNoScenesAndAnEmptyState() = runBlocking<Unit> {
        assertEquals(emptyList<String>(), all())
        assertEquals("No scenes here yet", ListKind.SCENES.empty)
        assertEquals(emptyList<Any>(), RecordListSource(ListKind.SCENES, emptyList(), data(), { Fixtures.ZONE }, { TODAY }, {}).load())
        assertEquals(0, data().times(ListKind.SCENES, range).first().size)
    }

    @Test fun scenesPageNewestFirstWithNoCapAndTheCountsAgree() = runBlocking<Unit> {
        for (i in 1..250) scene("s%03d".format(i), t0 + i * 1_000L)
        scene("outside", Fixtures.at(TODAY.minusDays(3), 9))
        val ids = all()
        assertEquals(250, ids.size); assertEquals("s250", ids.first()); assertEquals("s001", ids.last())
        assertEquals("the tile count and the list agree", 250, data().times(ListKind.SCENES, range).first().size)
        assertEquals(250, data().sceneTimes(range).size)
    }

    @Test fun aRowCarriesItsFreshWordsItsActivityAndItsMomentCount() = runBlocking<Unit> {
        scene("s1", t0, moments = listOf("0000000a", "0000000b"), activity = "WALKING", text = "Walking to the shop")
        val page = data().page(ListKind.SCENES, range, Cursor.head(range), 10)
        val row = Previewables.factory(ListKind.SCENES).load(page.ids, PreviewCtx(page.data, Fixtures.ZONE, TODAY)).single().summary()
        assertEquals("Walking to the shop", row.title)
        assertEquals("Today 09:00 to 09:01 · Walking · 2 moments", row.subtitle)
    }

    @Test fun aHiddenOrObsoleteSummaryNeverShows() = runBlocking<Unit> {
        scene("old", t0, text = "Built from an older revision", fresh = false, activity = "STILL")
        scene("hid", t0 + 1_000, text = "Hidden after a Forget", activity = "STILL")
        graph.db.objectDao().markStaleTx("SCENE", "hid", t0 + 2_000, "forget", "MOMENT", "0000000a")
        val cards = data().scenes(listOf("old", "hid"))
        assertEquals(2, cards.size)
        assertTrue("no old text leaks", cards.all { it.text == null })
        val rows = Previewables.factory(ListKind.SCENES).load(listOf("old", "hid"), PreviewCtx(data(), Fixtures.ZONE, TODAY)).map { it.summary() }
        assertTrue(rows.all { it.title == "Scene: Still" })
    }

    @Test fun searchFindsASceneByItsWordsItsActivityOrItsTime() = runBlocking<Unit> {
        scene("s1", t0, activity = "WALKING", text = "Walking to the shop"); scene("s2", t0 + 120_000, activity = "STILL", text = "Reading at the desk")
        val rows = RecordListSource(ListKind.SCENES, listOf("s1", "s2"), data(), { Fixtures.ZONE }, { TODAY }, {}).load()
        // the list screen filters the loaded rows on title and subtitle (contains, ignoring case)
        fun find(q: String) = rows.filter { it.title.contains(q, true) || it.subtitle?.contains(q, true) == true }.map { it.id }
        assertEquals(listOf("s1"), find("shop")); assertEquals(listOf("s2"), find("still")); assertEquals(listOf("s2", "s1"), find("today 09"))
    }

    @Test fun thePreviewShowsTheMemberMomentsByReferenceAndNothingAboutAPlace() = runBlocking<Unit> {
        graph.db.memoryDao().insert(Fixtures.memory("m1", "Keys on the shelf", t0 + 5_000, frameId = "f1"))
        graph.db.momentDao().replace(MomentEntity("0000000a", 1, 1, "h", 1, MomentType.FRAME),
            listOf(MomentPartEntity("0000000a", MomentPartKind.MEMORY, "m1", "r", 1), MomentPartEntity("0000000a", MomentPartKind.FRAME, "f1", "r", 1)))
        graph.db.momentDao().replace(MomentEntity("0000000b", 1, 1, "h", 1, MomentType.FRAME), listOf(MomentPartEntity("0000000b", MomentPartKind.FRAME, "f2", "r", 1)))
        scene("s1", t0, moments = listOf("0000000a", "0000000b"), text = "Tidying up")
        val item = Previewables.forRef(com.rokid.context.phone.ui.preview.RefKind.SCENE).load(listOf("s1"), PreviewCtx(data(), Fixtures.ZONE, TODAY, withDay = true)).single()
        val members = item.sections().filterIsInstance<PreviewSection.SourcesSection>().single().items
        assertEquals(listOf("memory:m1", "frame:f2"), members.map { it.ref })
        assertNull(item.forgetTarget())
        val everything = item.summary().toString() + item.sections().toString()
        assertFalse("no coordinates and no place", Regex("lat|lon|place|coordinate", RegexOption.IGNORE_CASE).containsMatchIn(everything))
    }

    @Test fun aForgottenSceneLeavesTheList() = runBlocking<Unit> {
        scene("s1", t0); scene("s2", t0 + 1_000)
        graph.db.sceneDao().remove(listOf("s1"))
        assertEquals(listOf("s2"), all())
    }
}
