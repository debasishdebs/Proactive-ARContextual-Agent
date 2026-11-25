package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.SceneMember
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.activity.MarkRowUi
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.list.RecordListSource
import com.rokid.context.phone.ui.preview.PreviewSection.AudioSection
import com.rokid.context.phone.ui.preview.PreviewSection.ConfigSection
import com.rokid.context.phone.ui.preview.PreviewSection.ImageSection
import com.rokid.context.phone.ui.preview.PreviewSection.ProposalSection
import com.rokid.context.phone.ui.preview.PreviewSection.RatingSection
import com.rokid.context.phone.ui.preview.PreviewSection.SourcesSection
import com.rokid.context.phone.ui.preview.PreviewSection.StatusSection
import com.rokid.context.phone.ui.preview.PreviewSection.TextSection
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** previewMoment's routing, each kind's presenter (which sections, in what order, what Forget), the registry and the list rows that share them. Model level: no screen. */
class PreviewMomentTest {
    private val t = Fixtures.at(TODAY, 9)
    private val frame = Fixtures.frame("f1", t)
    private val data = FakeActivityData(
        mems = listOf(Fixtures.memory("m1", "Keys on the shelf", t, frameId = "f1", tags = "keys, home"), Fixtures.memory("m2", "Typed note", t + 1)),
        frames = listOf(frame, Fixtures.frame("f2", t + 60_000)),
        marks = listOf(
            MarkRowUi("k1", t, "09:00", "", "In a Moment with a photo", null, frameId = "f1", memoryId = "m1", date = TODAY),
            MarkRowUi("k2", t, "09:00", "", "x", null, frameId = "f2", date = TODAY),
            MarkRowUi("k3", t, "09:00", "", "Moment with no photo", "No photo: the camera was busy", date = TODAY, reasonCode = "camera_busy"),
        ),
        asks = listOf(
            Fixtures.ask("a1", t),
            Fixtures.ask("a2", t, sources = """["m1","m2","gone","frame:f1","frame:f9"]""", rating = 1),
            Fixtures.ask("a3", t, sources = """["m1"]""", redacted = true, answer = "Removed \u2014 a source was forgotten"),
        ),
        nudges = listOf(
            Fixtures.nudge("n1", t),
            Fixtures.nudge("n2", t).copy(sourceIdsJson = """["m1","gone"]"""),
            Fixtures.nudge("n3", t, why = "secret why").copy(sourceIdsJson = """["m1"]""", redacted = true),
        ),
        scenes = listOf(com.rokid.context.memory.SceneCard(SceneEntity("s1", 1, t, t, t + 240_000, SceneRule.CAP_5_MIN, activity = "HEAD_MOVING", membershipRev = 1, builtAt = t), "Sorting keys at the door", 2)),
        members = mapOf("s1" to listOf(
            SceneMember("0000000a", t, listOf("m1"), listOf("f1"), emptyList()),
            SceneMember("0000000b", t + 60_000, emptyList(), listOf("f2"), emptyList()),
            SceneMember("0000000c", t + 120_000, emptyList(), emptyList(), listOf("k3")),
        )),
    )
    private fun ctx(withDay: Boolean = true) = PreviewCtx(data, Fixtures.ZONE, TODAY, withDay)
    private fun one(kind: RefKind, id: String): Previewable = runBlocking { Previewables.forRef(kind).load(listOf(id), ctx()).single() }
    private fun kinds(p: Previewable) = p.sections().map { it::class.simpleName }

    // previewMoment routing ---------------------------------------------------------------------------------------------------------------------------------

    @Test fun oneItemOpensItsPreviewerOfAnyKind() {
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.MEMORY, "m1")), previewMoment(listOf("memory:m1"), PreviewMode.OPEN))
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.FRAME, "f1")), previewMoment(listOf("frame:f1")))
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.MARK, "k1")), previewMoment(listOf("mark:k1")))
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.ASK, "a1")), previewMoment(listOf("ask:a1")))
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.NUDGE, "n1")), previewMoment(listOf("nudge:n1")))
        assertEquals(Routes.preview("memory:m1"), previewMoment(listOf("memory:m1")).route)
    }

    @Test fun severalItemsOrListModeOpenTheCommonListOfTheirKind() {
        assertEquals(PreviewDestination.Items(ListKind.FRAMES, listOf("f1", "f2")), previewMoment(listOf("frame:f1", "frame:f2")))
        assertEquals(PreviewDestination.Items(ListKind.MARKS, listOf("k1")), previewMoment(listOf("mark:k1"), PreviewMode.LIST))
        assertEquals("a memory is in both lists: the config names which", PreviewDestination.Items(ListKind.MOMENTS, listOf("m1", "m2")), previewMoment(listOf("memory:m1", "memory:m2")))
        assertEquals(PreviewDestination.Items(ListKind.MEMORIES, listOf("m1", "m2")), previewMoment(listOf("memory:m1", "memory:m2"), config = PreviewConfig(ListKind.MEMORIES)))
        assertEquals("mixed kinds fall back to the configured list, holding only its own kind", PreviewDestination.Items(ListKind.MOMENTS, listOf("m1")), previewMoment(listOf("memory:m1", "frame:f1")))
    }

    @Test fun anIdThatNamesNothingOpensTheEmptyListNeverADeadTap() {
        assertEquals(PreviewDestination.Items(ListKind.MOMENTS, emptyList()), previewMoment(listOf("junk", "memory:")))
        assertEquals(PreviewDestination.Items(ListKind.MOMENTS, emptyList()), previewMoment(emptyList()))
        assertEquals(Routes.list(ListKind.FRAMES, listOf("a", "b")), com.rokid.context.phone.ui.list.listView(ListKind.FRAMES, listOf("a", "b")))
    }

    // Each kind's presenter ----------------------------------------------------------------------------------------------------------------------------------

    @Test fun aCaptionedMomentHasPhotoCaptionConfigAndAudioAndForgetsTheFact() {
        val p = one(RefKind.MEMORY, "m1")
        assertEquals(listOf("ImageSection", "TextSection", "ConfigSection", "AudioSection"), kinds(p))
        assertEquals(ImageSection(listOf("f1"), "f1", canForgetPhoto = true, openDay = true), p.sections()[0])
        assertEquals("keys · home", ((p.sections()[2] as ConfigSection).entries).toMap()["Tags"])
        assertEquals(ForgetTarget.Fact("m1"), p.forgetTarget())
    }

    @Test fun aCaptionAloneHasNoImageSection() {
        assertEquals(listOf("TextSection", "ConfigSection", "AudioSection"), kinds(one(RefKind.MEMORY, "m2")))
    }

    @Test fun aPhotoWithNoCaptionIsTheDaysPhotosOpenedAtItAndForgetsThePhoto() {
        val p = one(RefKind.FRAME, "f2")
        assertEquals(ImageSection(listOf("f1", "f2"), "f2", canForgetPhoto = true, labels = listOf("09:00", "09:01")), p.sections().first())
        assertEquals(AudioSection, p.sections().last())
        assertNull("Forget photo is the section's: it follows the photo on screen", p.forgetTarget())
    }

    @Test fun photoAfterForgetStaysOnTheNeighbourAndNullWhenNoneRemain() {
        assertEquals("c", photoAfterForget(listOf("a", "b", "c"), "b"))
        assertEquals("b", photoAfterForget(listOf("a", "b", "c"), "c"))
        assertEquals("b", photoAfterForget(listOf("a", "b"), "a"))
        assertNull(photoAfterForget(listOf("a"), "a"))
        assertNull(photoAfterForget(emptyList(), "a"))
    }

    @Test fun aMarkShowsItsMomentsCaptionOrItsPhotoOrOnlyWhyThereIsNone() {
        assertEquals(kinds(one(RefKind.MEMORY, "m1")), kinds(one(RefKind.MARK, "k1")))
        assertEquals(ForgetTarget.Fact("m1"), one(RefKind.MARK, "k1").forgetTarget())
        assertEquals(listOf("f1", "f2"), (one(RefKind.MARK, "k2").sections().first() as ImageSection).frameIds)
        assertNull("a photo-only mark forgets the photo on screen, through its section", one(RefKind.MARK, "k2").forgetTarget())
        val none = one(RefKind.MARK, "k3")
        assertEquals(
            listOf(TextSection("Time", "09:00"), StatusSection("camera_busy", "No photo: the camera was busy", Severity.WARNING, showTimeOn = TODAY, showTimeAtMs = t), AudioSection),
            none.sections(),
        )
        assertEquals("a mark with no photo is forgotten as the mark (its fallback Moment goes with it)", ForgetTarget.Mark("k3"), none.forgetTarget())
    }

    @Test fun anAnswerHasQuestionAnswerRatingActionCardSourcesAndNothingToForget() {
        val p = one(RefKind.ASK, "a2")
        assertEquals(listOf("TextSection", "TextSection", "RatingSection", "ProposalSection", "SourcesSection", "AudioSection"), kinds(p))
        assertEquals(RatingSection(1), p.sections()[2]); assertEquals(ProposalSection("a2"), p.sections()[3])
        assertEquals("You asked \u00b7 Mon 12 Oct \u00b7 09:00", (p.sections()[0] as TextSection).label)
        assertNull("no Forget of its own (v1.1 backlog)", p.forgetTarget())
        assertNull(one(RefKind.NUDGE, "n1").forgetTarget())
    }

    @Test fun anAnswersSourcesAreItsLiveCitesAndAPhotoCiteIsNeverMissing() {
        val src = one(RefKind.ASK, "a2").sections().filterIsInstance<SourcesSection>().single()
        // m1 (with its photo), m2 (plain), the photo f1 (opens the day pager), the expired photo f9 (inert, kept); "gone" is the one missing source.
        assertEquals(listOf("memory:m1", "memory:m2", "frame:f1", null), src.items.map { it.ref })
        assertEquals(1, src.missing)
        assertEquals("f1", src.items[0].frameId); assertEquals(true, src.items[3].expired); assertEquals("f9", src.items[3].photoId)
        assertEquals("More photos searches the question", "Where are my keys?", src.moreQuery)
        assertEquals(0.8, src.items[0].confidence!!, 0.0)
    }

    @Test fun aRedactedAnswerShowsItsWordsOnlyNoRatingNoSources() {
        val p = one(RefKind.ASK, "a3")
        assertEquals(listOf("TextSection", "TextSection", "ProposalSection", "AudioSection"), kinds(p))
        assertEquals("Removed \u2014 a source was forgotten", (p.sections()[1] as TextSection).body)
    }

    @Test fun aNudgeHasTextWhyLiveSourcesAndTheActionCardAndARedactedOneOnlyItsNotice() {
        val p = one(RefKind.NUDGE, "n2")
        assertEquals(listOf("TextSection", "TextSection", "SourcesSection", "ProposalSection", "AudioSection"), kinds(p))
        assertEquals("Why", (p.sections()[1] as TextSection).label)
        assertEquals(listOf("memory:m1"), (p.sections()[2] as SourcesSection).items.map { it.ref })
        assertEquals(listOf("TextSection", "TextSection", "ProposalSection", "AudioSection"), kinds(one(RefKind.NUDGE, "n1")))
        val red = one(RefKind.NUDGE, "n3")
        assertEquals(listOf("TextSection", "AudioSection"), kinds(red))
        assertEquals("Removed \u2014 a source was forgotten", (red.sections()[0] as TextSection).body)
    }

    @Test fun everyPreviewAlwaysEndsWithTheAudioPlaceholderAndSectionsHoldOnlyIdsAndText() {
        listOf(RefKind.MEMORY to "m1", RefKind.MEMORY to "m2", RefKind.FRAME to "f1", RefKind.MARK to "k1", RefKind.MARK to "k3", RefKind.ASK to "a1", RefKind.ASK to "a2", RefKind.NUDGE to "n1", RefKind.NUDGE to "n3").forEach { (k, id) ->
            assertEquals("$k $id", AudioSection, one(k, id).sections().last())
        }
        assertEquals("a photo section carries ids, not paths or bytes", listOf("f1"), (one(RefKind.MEMORY, "m1").sections().first() as ImageSection).frameIds)
    }

    @Test fun aRetentionDeletedPhotoStillOpensAndAMissingItemIsAbsent() {
        val gone = runBlocking { Previewables.forRef(RefKind.FRAME).load(listOf("zz"), ctx()).single() }
        assertEquals(ImageSection(listOf("zz"), "zz", canForgetPhoto = true), gone.sections().first())
        assertTrue(runBlocking { Previewables.forRef(RefKind.MEMORY).load(listOf("nope"), ctx()).isEmpty() })
    }

    // Registry and lists -------------------------------------------------------------------------------------------------------------------------------------

    @Test fun everyListKindAndEveryRefKindHasAPreviewable() {
        assertEquals(ListKind.entries.toSet(), Previewables.registered())
        RefKind.entries.forEach { Previewables.forRef(it) }
        ListKind.entries.forEach { assertEquals(it.ref, ListKind.forRef(it.ref, it).ref) }
    }

    @Test fun listRowsAreThePresentersSummariesAndATapPreviewsTheItem() = runBlocking<Unit> {
        val opened = mutableListOf<String>()
        val rows = mapOf(
            ListKind.FRAMES to listOf("f1", "f2"), ListKind.MEMORIES to listOf("m1", "m2"), ListKind.MOMENTS to listOf("m1"), ListKind.MARKS to listOf("k1", "k3"),
            ListKind.ANSWERS to listOf("a1", "a2"), ListKind.NUDGES to listOf("n1"), ListKind.SCENES to listOf("s1"),
        )
        rows.forEach { (kind, ids) ->
            val src = RecordListSource(kind, ids, data, { Fixtures.ZONE }, { TODAY }, onPreview = { opened += it })
            val items = src.load()
            assertEquals(kind.name, ids.toSet(), items.map { it.id }.toSet())
            assertEquals(src.emptyText, kind.empty)
            items.forEach { src.actionFor(it)!!.run { } }
        }
        assertEquals(listOf("frame:f2", "frame:f1"), opened.take(2))
        assertTrue("scene:s1" in opened)
        assertTrue("mark:k3" in opened && "memory:m2" in opened && "ask:a1" in opened && "nudge:n1" in opened)
        // An empty kind: no rows (the list shows its empty state) and no read.
        assertEquals(emptyList<Any>(), RecordListSource(ListKind.NUDGES, emptyList(), data, { Fixtures.ZONE }, { TODAY }, {}).load())
        assertEquals("a moment row says whether a photo goes with it", true, RecordListSource(ListKind.MOMENTS, listOf("m1"), data, { Fixtures.ZONE }, { TODAY }, {}).load().single().subtitle!!.endsWith("with photo"))
    }

    // Scenes (Activity > Scenes list) ---------------------------------------------------------------------------------------------------------------------------

    @Test fun aSceneRowSaysWhenWhatAndHowManyAndNeverHoldsAPlace() = runBlocking<Unit> {
        val row = RecordListSource(ListKind.SCENES, listOf("s1"), data, { Fixtures.ZONE }, { TODAY }, {}).load().single()
        assertEquals("Sorting keys at the door", row.title)
        assertEquals("Today 09:00 to 09:04 \u00b7 Head moving \u00b7 2 moments", row.subtitle)
        assertEquals("a scene with no words says its activity, never an empty row", "Scene: Walking",
            ScenePreview("x", com.rokid.context.memory.SceneCard(SceneEntity("x", 1, t, t, t + 1000, SceneRule.HOUR_END, activity = "WALKING", membershipRev = 1, builtAt = t), null, 1), emptyList(), ctx()).summary().title)
        assertEquals("a Scene that vanished keeps its row slot", "This Scene is no longer available", ScenePreview("gone", null, emptyList(), ctx()).summary().subtitle)
    }

    @Test fun aSceneOpensItsSummaryTimeAndMemberMomentsAndHasNoForgetOfItsOwn() {
        val p = one(RefKind.SCENE, "s1")
        assertEquals(listOf("TextSection", "ConfigSection", "SourcesSection"), kinds(p))
        assertEquals(null, p.forgetTarget())
        val cfg = p.sections().filterIsInstance<ConfigSection>().single().entries.toMap()
        assertEquals("4 min", cfg["Length"]); assertEquals("Head moving", cfg["Activity"]); assertEquals("2", cfg["Moments"]); assertEquals("5 minute limit", cfg["Cut by"])
        val members = p.sections().filterIsInstance<PreviewSection.SourcesSection>().single().items
        assertEquals("a tap opens the existing memory preview, else the photo, else the mark", listOf("memory:m1", "frame:f2", "mark:k3"), members.map { it.ref })
        assertEquals("the list reads no members", emptyList<Any>(), runBlocking { Previewables.forRef(RefKind.SCENE).load(listOf("s1"), ctx(withDay = false)).single().sections().filterIsInstance<PreviewSection.SourcesSection>() })
    }

    @Test fun sceneWordsAreFixedAndPlain() {
        assertEquals("Head moving", SceneWords.activity("HEAD_MOVING")); assertEquals(null, SceneWords.activity(" ")); assertEquals(null, SceneWords.activity(null))
        assertEquals("40 s", SceneWords.length(0, 40_000)); assertEquals("5 min", SceneWords.length(0, 299_999)); assertEquals("0 s", SceneWords.length(5, 1))
        assertEquals("Other", SceneWords.rule("NEW_RULE_FROM_THE_FUTURE"))
    }

    /** A Month-sized list: the route carries a short key, not 20,000 ids, and the rows are read 200 at a time. */
    @Test fun aMonthSizedListOpensByKeyAndPages() = runBlocking<Unit> {
        val big = (1..20_000).map { "f$it" }
        val readIds = mutableListOf<Int>()
        val data = object : ActivityData {
            override suspend fun memories(ids: List<String>) = emptyList<MemoryEntity>()
            override suspend fun frames(ids: List<String>) = ids.also { readIds += it.size }.map { Fixtures.frame(it, t) }
            override suspend fun marks(ids: List<String>) = emptyList<MarkRowUi>()
            override suspend fun asks(ids: List<String>) = emptyList<AskEntity>()
            override suspend fun nudges(ids: List<String>) = emptyList<NudgeEntity>()
            override suspend fun dayFrames(date: LocalDate) = emptyList<FrameEntity>()
            override suspend fun page(kind: ListKind, range: LongRange, after: com.rokid.context.memory.Cursor, n: Int): ActivityPage {
                val from = if (after.id.isEmpty()) 0 else big.indexOf(after.id) + 1
                val ids = big.drop(from).take(n)
                return ActivityPage(ids, ids.map { com.rokid.context.memory.Cursor(1, it) }, this)
            }
        }
        val route = com.rokid.context.phone.ui.list.listView(ListKind.FRAMES, com.rokid.context.phone.ui.activity.Period.MONTH, TODAY)
        assertEquals("list/FRAMES?scope=MONTH%3A2026-10-12", route)
        assertTrue(route.length < 60)
        val scope = com.rokid.context.phone.ui.list.ListScope.parse("MONTH:2026-10-12")!!
        val src = RecordListSource(ListKind.FRAMES, emptyList(), data, { Fixtures.ZONE }, { TODAY }, {}, scope = scope)
        val pagerScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        src.startIn(pagerScope)
        withTimeout(5_000) { src.pane.first { it.loaded } }
        assertEquals(100, src.pane.value.rows.size); assertTrue(src.pane.value.hasMore)
        repeat(3) { val n = src.pane.value.rows.size; src.loadMore(); withTimeout(5_000) { src.pane.first { it.rows.size > n } } }
        assertEquals(400, src.pane.value.rows.size)
        assertEquals("only the shown pages are ever read, 100 a page", listOf(100, 100, 100, 100), readIds.toList())
        pagerScope.cancel()
        assertEquals(null, com.rokid.context.phone.ui.list.ListScope.parse("junk")); assertEquals(null, com.rokid.context.phone.ui.list.ListScope.parse("WEEK:nope"))
    }
}
