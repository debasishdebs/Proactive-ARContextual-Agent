package com.rokid.context.phone.ui.preview

import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.activity.MarkRowUi
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.list.RecordListSource
import com.rokid.context.phone.ui.preview.PreviewSection.AudioSection
import com.rokid.context.phone.ui.preview.PreviewSection.ConfigSection
import com.rokid.context.phone.ui.preview.PreviewSection.ImageSection
import com.rokid.context.phone.ui.preview.PreviewSection.StatusSection
import com.rokid.context.phone.ui.preview.PreviewSection.TextSection
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** previewMoment's routing, each kind's presenter (which sections, in what order, what Forget), the registry and the list rows that share them. Model level: no screen. */
class PreviewMomentTest {
    private class Fake(
        val mems: List<MemoryEntity> = emptyList(), val frames: List<FrameEntity> = emptyList(), val marks: List<MarkRowUi> = emptyList(),
        val asks: List<AskEntity> = emptyList(), val nudges: List<NudgeEntity> = emptyList(),
    ) : ActivityData {
        override suspend fun memories(ids: List<String>) = mems.filter { it.id in ids }
        override suspend fun frames(ids: List<String>) = frames.filter { it.id in ids }
        override suspend fun marks(ids: List<String>) = marks.filter { it.labelId in ids }
        override suspend fun asks(ids: List<String>) = asks.filter { it.id in ids }
        override suspend fun nudges(ids: List<String>) = nudges.filter { it.id in ids }
        override suspend fun dayFrameIds(date: LocalDate) = frames.map { it.id }
    }

    private val t = Fixtures.at(TODAY, 9)
    private val frame = Fixtures.frame("f1", t)
    private val data = Fake(
        mems = listOf(Fixtures.memory("m1", "Keys on the shelf", t, frameId = "f1", tags = "keys, home"), Fixtures.memory("m2", "Typed note", t + 1)),
        frames = listOf(frame, Fixtures.frame("f2", t + 60_000)),
        marks = listOf(
            MarkRowUi("k1", t, "09:00", "", "In a Moment with a photo", null, frameId = "f1", memoryId = "m1", date = TODAY),
            MarkRowUi("k2", t, "09:00", "", "x", null, frameId = "f2", date = TODAY),
            MarkRowUi("k3", t, "09:00", "", "Moment with no photo", "No photo: the camera was busy", date = TODAY, reasonCode = "camera_busy"),
        ),
        asks = listOf(Fixtures.ask("a1", t)), nudges = listOf(Fixtures.nudge("n1", t)),
    )
    private fun ctx(withDay: Boolean = true) = PreviewCtx(data, Fixtures.ZONE, TODAY, withDay)
    private fun one(kind: RefKind, id: String): Previewable = runBlocking { Previewables.forRef(kind).load(listOf(id), ctx()).single() }
    private fun kinds(p: Previewable) = p.sections().map { it::class.simpleName }

    // previewMoment routing ---------------------------------------------------------------------------------------------------------------------------------

    @Test fun oneItemOpensItsPreviewerAnAnswerOrNudgeItsOwnDetail() {
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.MEMORY, "m1")), previewMoment(listOf("memory:m1"), PreviewMode.OPEN))
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.FRAME, "f1")), previewMoment(listOf("frame:f1")))
        assertEquals(PreviewDestination.Screen(PreviewRef(RefKind.MARK, "k1")), previewMoment(listOf("mark:k1")))
        assertEquals(PreviewDestination.Detail(Routes.ask("a1")), previewMoment(listOf("ask:a1")))
        assertEquals(PreviewDestination.Detail(Routes.nudge("n1")), previewMoment(listOf("nudge:n1")))
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
        assertEquals(ImageSection(listOf("f1"), "f1", canForgetPhoto = true), p.sections()[0])
        assertEquals("keys · home", ((p.sections()[2] as ConfigSection).entries).toMap()["Tags"])
        assertEquals(ForgetTarget.Fact("m1"), p.forgetTarget())
    }

    @Test fun aCaptionAloneHasNoImageSection() {
        assertEquals(listOf("TextSection", "ConfigSection", "AudioSection"), kinds(one(RefKind.MEMORY, "m2")))
    }

    @Test fun aPhotoWithNoCaptionIsTheDaysPhotosOpenedAtItAndForgetsThePhoto() {
        val p = one(RefKind.FRAME, "f2")
        assertEquals(listOf(ImageSection(listOf("f1", "f2"), "f2"), AudioSection), p.sections())
        assertEquals(ForgetTarget.Photo("f2"), p.forgetTarget())
    }

    @Test fun aMarkShowsItsMomentsCaptionOrItsPhotoOrOnlyWhyThereIsNone() {
        assertEquals(kinds(one(RefKind.MEMORY, "m1")), kinds(one(RefKind.MARK, "k1")))
        assertEquals(ForgetTarget.Fact("m1"), one(RefKind.MARK, "k1").forgetTarget())
        assertEquals(listOf(ImageSection(listOf("f1", "f2"), "f2"), AudioSection), one(RefKind.MARK, "k2").sections())
        val none = one(RefKind.MARK, "k3")
        assertEquals(
            listOf(TextSection("Time", "09:00"), StatusSection("camera_busy", "No photo: the camera was busy", Severity.WARNING, showTimeOn = TODAY), AudioSection),
            none.sections(),
        )
        assertNull("a mark with no photo has nothing to forget here", none.forgetTarget())
    }

    @Test fun anAnswerAndANudgeHaveTextAndNothingToForget() {
        assertEquals(listOf("TextSection", "TextSection", "AudioSection"), kinds(one(RefKind.ASK, "a1")))
        assertEquals(listOf("TextSection", "TextSection", "AudioSection"), kinds(one(RefKind.NUDGE, "n1")))
        assertNull(one(RefKind.ASK, "a1").forgetTarget()); assertNull(one(RefKind.NUDGE, "n1").forgetTarget())
    }

    @Test fun everyPreviewAlwaysEndsWithTheAudioPlaceholderAndSectionsHoldOnlyIdsAndText() {
        listOf(RefKind.MEMORY to "m1", RefKind.MEMORY to "m2", RefKind.FRAME to "f1", RefKind.MARK to "k1", RefKind.MARK to "k3", RefKind.ASK to "a1", RefKind.NUDGE to "n1").forEach { (k, id) ->
            assertEquals("$k $id", AudioSection, one(k, id).sections().last())
        }
        assertEquals("a photo section carries ids, not paths or bytes", listOf("f1"), (one(RefKind.MEMORY, "m1").sections().first() as ImageSection).frameIds)
    }

    @Test fun aRetentionDeletedPhotoStillOpensAndAMissingItemIsAbsent() {
        val gone = runBlocking { Previewables.forRef(RefKind.FRAME).load(listOf("zz"), ctx()).single() }
        assertEquals(ImageSection(listOf("zz"), "zz"), gone.sections().first())
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
            ListKind.ANSWERS to listOf("a1"), ListKind.NUDGES to listOf("n1"),
        )
        rows.forEach { (kind, ids) ->
            val src = RecordListSource(kind, ids, data, { Fixtures.ZONE }, { TODAY }, onPreview = { opened += it })
            val items = src.load()
            assertEquals(kind.name, ids.toSet(), items.map { it.id }.toSet())
            assertEquals(src.emptyText, kind.empty)
            items.forEach { src.actionFor(it)!!.run { } }
        }
        assertEquals(listOf("frame:f2", "frame:f1"), opened.take(2))
        assertTrue("mark:k3" in opened && "memory:m2" in opened && "ask:a1" in opened && "nudge:n1" in opened)
        // An empty kind: no rows (the list shows its empty state) and no read.
        assertEquals(emptyList<Any>(), RecordListSource(ListKind.NUDGES, emptyList(), data, { Fixtures.ZONE }, { TODAY }, {}).load())
        assertEquals("a moment row says whether a photo goes with it", true, RecordListSource(ListKind.MOMENTS, listOf("m1"), data, { Fixtures.ZONE }, { TODAY }, {}).load().single().subtitle!!.endsWith("with photo"))
    }
}
