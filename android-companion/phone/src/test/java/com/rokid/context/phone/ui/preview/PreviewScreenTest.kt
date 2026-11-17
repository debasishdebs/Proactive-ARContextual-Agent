package com.rokid.context.phone.ui.preview

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.memory.ForgetCopy
import com.rokid.context.phone.ui.memory.ForgetPreview
import com.rokid.context.phone.ui.memory.ForgetScopeCopy
import com.rokid.context.phone.ui.memory.MemoryDetailUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one previewer on screen, per kind: Forget (the shared dialog and its scope), Trust, Edit, review state, the day pager, the rating, the cited sources, the action card slot.
 * The old Memory, Photo viewer, Answer and Nudge screens' behaviour is covered here, through the sections.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PreviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private val t = Fixtures.at(TODAY, 9)
    private val data = FakeActivityData(
        mems = listOf(
            Fixtures.memory("m1", "Keys on shelf", t, frameId = "f1"),
            Fixtures.memory("m2", "Typed note", t + 1),
            Fixtures.memory("m3", "Hidden one", t + 2, review = com.rokid.context.memory.db.ReviewState.INVALIDATED),
        ),
        frames = listOf(Fixtures.frame("f1", t), Fixtures.frame("f2", t + 300_000)),
        asks = listOf(Fixtures.ask("a1", t, sources = """["m2","gone","frame:f1","frame:f9"]""", rating = 1), Fixtures.ask("a2", t, sources = """["m2"]""", redacted = true, answer = "Removed — a source was forgotten")),
        nudges = listOf(Fixtures.nudge("n1", t, text = "Milk is nearly out", why = "You saw an empty carton").copy(sourceIdsJson = """["m2","m3"]""")),
    )
    private fun load(kind: RefKind, id: String): Previewable = runBlocking { Previewables.forRef(kind).load(listOf(id), PreviewCtx(data, Fixtures.ZONE, TODAY, withDay = true)).single() }

    /** What the memory detail view model would say for the memory on screen. */
    private val base = MemoryDetailUiState(loading = false, found = true, text = "Keys on shelf", tierLabel = "Short-term", reviewLabel = "Reviewed", confidence = 0.8, frameId = "f1")
    private val forgetAsked = mutableListOf<ForgetTarget>()
    private val photosForgotten = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private var confirmed = 0
    private var trusts = 0; private var edits = 0; private var saves = 0

    private fun show(item: Previewable?, detail: MemoryDetailUiState = base, loading: Boolean = false, tweak: (PreviewHost) -> PreviewHost = { it }) = compose.setContent {
        val host = PreviewHost(
            detail, framePath = { null }, forgetPhoto = { photosForgotten += it }, trust = { trusts++ }, edit = { edits++ }, saveEdit = { saves++ },
            open = { opened += it }, proposal = { id -> Text("proposal card for $id") },
        )
        PcTheme { PreviewScreen(PreviewUiState(loading, item), tweak(host), onBack = {}, onForget = { forgetAsked += it }, onConfirmForget = { confirmed++ }) }
    }

    // Memory: Forget, Trust, Edit, review state ---------------------------------------------------------------------------------------------------------------

    @Test fun aMemoryShowsItsCaptionReviewStateAndConfidenceAndTrustAndEditWork() {
        show(load(RefKind.MEMORY, "m1"))
        compose.onNodeWithText("Keys on shelf").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Level: Short-term \u00b7 Reviewed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Confidence: 80%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Trust").performScrollTo().performClick(); compose.onNodeWithText("Edit").performScrollTo().performClick()
        assertEquals(1, trusts); assertEquals(1, edits)
    }

    @Test fun editingShowsTheDraftWithSaveAndCancelAndBlocksTheOtherActions() {
        show(load(RefKind.MEMORY, "m1"), base.copy(draft = "Keys on hallway shelf"))
        compose.onNodeWithText("Keys on hallway shelf").assertIsDisplayed()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(1, saves)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Trust").assertIsNotEnabled(); compose.onNodeWithText("Edit").assertIsNotEnabled(); compose.onNodeWithTag("preview_forget").assertIsNotEnabled()
    }

    @Test fun forgetFactAsksForTheFactAndForgetPhotoForThePhotoOnScreen() {
        show(load(RefKind.MEMORY, "m1"))
        compose.onNodeWithTag("preview_forget").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf<ForgetTarget>(ForgetTarget.Fact("m1")), forgetAsked)
        compose.onNodeWithTag("forget_photo").performScrollTo().performClick()
        assertEquals(listOf("f1"), photosForgotten)
    }

    @Test fun aMemoryWithNoPhotoHasNoForgetPhoto() {
        show(load(RefKind.MEMORY, "m2"))
        compose.onNodeWithTag("forget_photo").assertDoesNotExist()
    }

    @Test fun aHiddenMemoryCannotBeTrustedEditedOrForgottenAsAFact() {
        show(load(RefKind.MEMORY, "m3"), base.copy(editable = false, frameId = null))
        compose.onNodeWithText("Trust").assertIsNotEnabled(); compose.onNodeWithText("Edit").assertIsNotEnabled()
        compose.onNodeWithTag("preview_forget").assertDoesNotExist()
    }

    @Test fun forgetButtonsAreDisabledWhileAForgetRuns() {
        show(load(RefKind.MEMORY, "m1"), base.copy(forgetting = true))
        compose.onNodeWithTag("preview_forget").assertIsNotEnabled(); compose.onNodeWithTag("forget_photo").assertIsNotEnabled()
    }

    @Test fun aMissingItemAndALoadingItemSayWhatTheyAre() {
        show(null)
        compose.onNodeWithText("This item is no longer available").assertIsDisplayed()
    }

    @Test fun aMemorysPhotoOpensItsDayPagedAndAPhotoThatIsGoneSaysSo() {
        show(load(RefKind.MEMORY, "m1"))
        compose.onNodeWithTag("photo_expired").assertIsDisplayed()
        compose.onNodeWithTag("open_day").performClick()
        assertEquals(listOf("frame:f1"), opened)
    }

    /** Device check D-E1: in a short viewport (landscape) the photo fills the screen; a one-finger drag on it must scroll the page. */
    /** Codex r21 P1-2: a photo on screen whose row retention (or a Forget, or Delete all) removes leaves the screen at once: the decoded image is dropped. */
    @Test fun aPhotoRemovedWhileOnScreenIsDroppedAndSaysSo() {
        val path = kotlinx.coroutines.flow.MutableStateFlow<String?>("/f1.jpg")
        show(load(RefKind.MEMORY, "m1")) { it.copy(framePath = { "/f1.jpg" }, observeFramePath = { path }) }
        compose.waitForIdle()
        compose.onAllNodesWithTag("photo_expired").assertCountEquals(0) // the photo view is on screen
        path.value = null // the row is gone
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("photo_expired").fetchSemanticsNodes().isNotEmpty() } // the photo view (and its decoded image) left
    }

    @Test fun dragOnUnzoomedPhotoScrollsThePage() {
        compose.setContent { PcTheme { Box(Modifier.height(360.dp)) { PreviewScreen(PreviewUiState(false, load(RefKind.MEMORY, "m1")), PreviewHost(base, framePath = { "/missing.jpg" }), onBack = {}, onForget = {}) } } }
        compose.onNodeWithTag("preview_image").performTouchInput { swipe(start = center, end = center - Offset(0f, 600f), durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithTag("preview_forget").assertIsDisplayed()
    }

    // The Forget dialog: scope in plain words, counts, acknowledgement ---------------------------------------------------------------------------------------

    private val scene = ForgetPreview(ForgetScope.SCENE, ForgetScope.SCENE, 4, 5, 1, 3, 1, 2)
    private fun dialog(d: MemoryDetailUiState) = show(load(RefKind.MEMORY, "m1"), d)
    private val momentDialog get() = base.copy(confirm = com.rokid.context.phone.ui.memory.ForgetKind.MOMENT)

    @Test fun theFactDialogCopyAndConfirm() {
        dialog(base.copy(confirm = com.rokid.context.phone.ui.memory.ForgetKind.FACT))
        compose.onNodeWithText("Forget this fact?").assertExists()
        compose.onNodeWithText("It's removed everywhere, and Proactive Context won't learn it from this photo again. Answers that used it will show as removed.").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(1, confirmed)
    }

    private fun singleDialog(kind: com.rokid.context.phone.ui.memory.ForgetKind, title: String) {
        dialog(base.copy(confirm = kind, forgetConfirmable = true))
        compose.onNodeWithText(title).assertExists()
        compose.onNodeWithText(ForgetCopy.SINGLE_SCOPE).assertExists()
        compose.onAllNodesWithText("This deletes", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Forget").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }
    @Test fun theMarkDialogSaysItIsOneItemAndHasNoScopeOrCounts() {
        dialog(base.copy(confirm = com.rokid.context.phone.ui.memory.ForgetKind.MARK, forgetConfirmable = true))
        compose.onNodeWithText("Forget this mark?").assertExists()
        compose.onNodeWithText(ForgetCopy.SINGLE_SCOPE).assertExists()
        compose.onAllNodesWithText("This deletes", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Forget").assertIsEnabled().performClick()
        assertEquals(1, confirmed)
    }

    @Test fun aRemovedAnswerOffersNoForget() {
        show(load(RefKind.ASK, "a2"))
        compose.onAllNodesWithText("Forget answer").assertCountEquals(0)
    }

    @Test fun theMomentDialogCopy() {
        dialog(momentDialog)
        compose.onNodeWithText("Forget this moment?").assertExists()
        compose.onNodeWithText("The photo and every fact from it are deleted. Answers that used them will show as removed.").assertExists()
    }

    @Test fun theDialogStatesTheScopeInPlainWordsAndTheCounts() {
        dialog(momentDialog.copy(forgetPreview = scene, forgetConfirmable = true))
        compose.onNodeWithText(ForgetScopeCopy.dialogScope(ForgetScope.SCENE)).assertExists()
        compose.onNodeWithText("This deletes 4 photos, 5 facts, 1 mark, 3 Moments. 1 Scene removed, 2 Scenes shrink.").assertExists()
        compose.onNodeWithText("Forget").assertIsEnabled()
    }

    @Test fun theDialogShowsTheEffectiveScopeNotTheSetting() {
        dialog(momentDialog.copy(forgetPreview = ForgetPreview(ForgetScope.SCENE, ForgetScope.MOMENT, 1, 1, 0, 1, 0, 0), forgetConfirmable = true))
        compose.onNodeWithText(ForgetScopeCopy.dialogScope(ForgetScope.MOMENT)).assertExists()
        compose.onNodeWithText("This deletes 1 photo, 1 fact, 0 marks, 1 Moment.").assertExists()
    }

    @Test fun whileCountingOrAfterAFailedCountForgetIsDisabledAndSaysWhy() {
        dialog(momentDialog.copy(forgetPreviewing = true))
        compose.onNodeWithText(ForgetScopeCopy.COUNTING).assertExists(); compose.onNodeWithText("Forget").assertIsNotEnabled()
    }

    @Test fun aFailedCountSaysSo() {
        dialog(momentDialog.copy(forgetPreviewFailed = true, forgetConfirmable = false))
        compose.onNodeWithText(ForgetScopeCopy.COUNTS_FAILED).assertExists(); compose.onNodeWithText("Forget").assertIsNotEnabled()
    }

    @Test fun theDialogShowsTheContentChangedMessageAndTheOutsideLine() {
        dialog(momentDialog.copy(forgetPreview = scene.copy(outside = 3), forgetConfirmable = true, message = ForgetCopy.CONTENT_CHANGED))
        compose.onNodeWithText("3 items outside this window are affected (not deleted).").assertExists()
        assertEquals("the page and the dialog both show it", 2, compose.onAllNodesWithText("Content changed since the preview. Review again.").fetchSemanticsNodes().size)
    }

    @Test fun aTooLargeForgetSaysSoShowsNoCountsAndCannotBeConfirmed() {
        dialog(momentDialog.copy(forgetPreview = scene.copy(tooLarge = true), forgetConfirmable = false))
        compose.onNodeWithText("Too much is connected to this to forget in one step; use a smaller scope or Delete all").assertExists(); compose.onNodeWithText("Forget").assertIsNotEnabled()
        compose.onNodeWithText("This deletes 4 photos, 5 facts, 1 mark, 3 Moments. 1 Scene removed, 2 Scenes shrink.").assertDoesNotExist()
    }

    @Test fun theScopeCopyIsPlainAndDistinct() {
        val all = ForgetScope.values()
        assertEquals(3, all.map { ForgetScopeCopy.dialogScope(it) }.distinct().size); assertEquals(listOf("Photo", "Moment", "Scene"), all.map { ForgetScopeCopy.label(it) })
        assertEquals("This deletes 1 photo, 1 fact, 1 mark, 1 Moment.", ForgetScopeCopy.counts(1, 1, 1, 1, 0, 0))
        assertEquals("This deletes 0 photos, 2 facts, 0 marks, 0 Moments. 2 Scenes removed.", ForgetScopeCopy.counts(0, 2, 0, 0, 2, 0))
        assertEquals("1 item outside this window is affected (not deleted).", ForgetScopeCopy.outsideLine(1))
    }

    // A photo: the day's pager ---------------------------------------------------------------------------------------------------------------------------------

    @Test fun swipingTheDaysPhotosMovesToTheNextOneAndShowsItsTime() {
        show(load(RefKind.FRAME, "f1"), MemoryDetailUiState(loading = false))
        compose.onNodeWithText("1 / 2 · 09:00").assertIsDisplayed()
        compose.onNodeWithTag("frame_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 2 · 09:05").assertIsDisplayed()
    }

    @Test fun aPhotoOpensAtItsFrameAndThePlayToggleWorks() {
        show(load(RefKind.FRAME, "f2"), MemoryDetailUiState(loading = false))
        compose.onNodeWithText("2 / 2 · 09:05").assertIsDisplayed()
        compose.mainClock.autoAdvance = false // keep the 1.5 s slideshow step from elapsing before the assertion
        compose.onNodeWithContentDescription("Play").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test fun forgetPhotoOnAFramePreviewTargetsThePhotoNowOnScreenNotTheOpeningOne() {
        show(load(RefKind.FRAME, "f1"), MemoryDetailUiState(loading = false))
        compose.onNodeWithTag("preview_forget").assertDoesNotExist()
        compose.onNodeWithTag("frame_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("2 / 2 \u00b7 09:05").assertIsDisplayed()
        compose.onNodeWithTag("forget_photo").performScrollTo().performClick()
        assertEquals("the swiped-to photo, never the one it opened on", listOf("f2"), photosForgotten)
        compose.onNodeWithTag("add_to_photo_test").assertDoesNotExist()
    }

    @Test fun aPhotoOnlyMarkForgetsThePhotoOnScreenToo() {
        val marks = FakeActivityData(frames = data.frames, marks = listOf(com.rokid.context.phone.ui.activity.MarkRowUi("k2", t, "09:00", "", "x", null, frameId = "f2", date = TODAY)))
        val p = runBlocking { Previewables.forRef(RefKind.MARK).load(listOf("k2"), PreviewCtx(marks, Fixtures.ZONE, TODAY, true)).single() }
        show(p, MemoryDetailUiState(loading = false))
        compose.onNodeWithTag("forget_photo").performScrollTo().performClick()
        assertEquals(listOf("f2"), photosForgotten)
    }

    @Test fun aForgottenOrMissingMemoryNeverShowsItsCachedWords() {
        show(load(RefKind.MEMORY, "m2"), com.rokid.context.phone.ui.memory.MemoryDetailModel.NOT_FOUND)
        compose.onNodeWithText("This memory is no longer available").assertIsDisplayed()
        compose.onAllNodesWithText("Typed note").assertCountEquals(0)
        compose.onAllNodesWithText("Trust").assertCountEquals(0)
    }

    @Test fun everyKindShowsAForgetFailureWithItsRetryHint() {
        show(load(RefKind.FRAME, "f1"), MemoryDetailUiState(loading = false, found = true, frameId = "f1", message = ForgetCopy.EXPORT_RUNNING))
        compose.onNodeWithTag("preview_message").assertIsDisplayed()
        compose.onNodeWithText(ForgetCopy.EXPORT_RUNNING).assertIsDisplayed()
    }

    @Test fun trustAndReviewStateFollowTheLiveMemoryNotTheOpeningSnapshot() {
        show(load(RefKind.MEMORY, "m1"), base.copy(trusted = true, reviewLabel = "Needs review"))
        compose.onNodeWithText("Level: Short-term \u00b7 Needs review \u00b7 Trusted").performScrollTo().assertIsDisplayed()
    }

    @Test fun addToPhotoTestSendsTheQueryTheFrameAndTheSmallFlag() {
        val added = mutableListOf<Triple<String, String, Boolean>>()
        show(load(RefKind.FRAME, "f2"), MemoryDetailUiState(loading = false)) { it.copy(addToPhotoTest = { q, id, small -> added += Triple(q, id, small) }) }
        compose.onNodeWithTag("add_to_photo_test").performClick()
        compose.onNodeWithTag("photo_test_add").assertIsNotEnabled() // nothing to add until a query is typed
        assertEquals(emptyList<Triple<String, String, Boolean>>(), added)
    }

    // Answer: rating, sources, action card ---------------------------------------------------------------------------------------------------------------------

    private val ratings = mutableListOf<Boolean>()
    private val more = mutableListOf<String>()
    private fun answer(id: String) = show(load(RefKind.ASK, id), MemoryDetailUiState(loading = false)) { it.copy(rate = { ratings += it }, morePhotos = { more += it }) }

    @Test fun anAnswerShowsQuestionAnswerSourcesAndTheActionCardAndOpensASource() {
        answer("a1")
        compose.onNodeWithText("Where are my keys?").assertIsDisplayed()
        compose.onNodeWithText("On the hallway shelf.").assertIsDisplayed()
        compose.onNodeWithText("proposal card for a1").assertIsDisplayed()
        compose.onNodeWithText("1 source is no longer available").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Typed note").performScrollTo().performClick()
        assertEquals(listOf("memory:m2"), opened)
    }

    @Test fun ratingShowsTheStoredChoiceAndReportsANewOne() {
        answer("a1")
        compose.onNodeWithContentDescription("Helpful").assertIsSelected()
        compose.onNodeWithContentDescription("Not helpful").performClick()
        assertEquals(listOf(false), ratings)
        compose.onNodeWithContentDescription("Not helpful").assertIsSelected()
    }

    @Test fun aCitedPhotoOpensItsDayAndAnExpiredOneIsInertWithThePlaceholder() {
        answer("a1")
        compose.onNodeWithTag("photo_source_f1").performScrollTo().performClick()
        assertEquals(listOf("frame:f1"), opened)
        compose.onNodeWithText("Photo expired").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("photo_source_f9").performClick()
        assertEquals("nothing opens for the expired card", 1, opened.size)
    }

    @Test fun morePhotosSearchesTheQuestion() {
        answer("a1")
        compose.onNodeWithTag("more_photos").performScrollTo().performClick()
        assertEquals(listOf("Where are my keys?"), more)
    }

    @Test fun aRedactedAnswerHasNoRatingNoSourcesNoMorePhotos() {
        answer("a2")
        compose.onNodeWithText("Removed — a source was forgotten").assertIsDisplayed()
        compose.onNodeWithContentDescription("Helpful").assertDoesNotExist()
        compose.onNodeWithTag("more_photos").assertDoesNotExist()
        compose.onAllNodesWithText("Typed note").assertCountEquals(0)
    }

    /** M5 (forgetV2): an answer whose source was forgotten keeps its question, says so, offers Ask again (its own tap, never automatic) and shows no rating, sources or answer text. */
    private fun forgottenAnswer(id: String, states: String) = FakeActivityData(asks = listOf(Fixtures.ask(id, t, question = "Where are my keys?", answer = "Removed \u2014 a source was forgotten", redacted = true, sourceStates = states)))
        .let { d -> runBlocking { AnswerPreview.load(listOf(id), PreviewCtx(d, Fixtures.ZONE, TODAY, withDay = true)).single() } }

    @Test fun aForgottenSourceAnswerKeepsItsQuestionAndOffersAskAgain() {
        var again = 0
        val item = forgottenAnswer("a3", com.rokid.context.memory.forget.AskSourceStates.forgotten())
        assertEquals("the Activity list line says so", true, item.summary().subtitle!!.endsWith("Answered from a forgotten fact"))
        show(item) { it.copy(askAgain = { again++ }) }
        compose.onAllNodesWithText("Where are my keys?").assertCountEquals(1)
        compose.onNodeWithText("Answered from a forgotten fact").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ask again: Where are my keys?").performScrollTo().performClick()
        assertEquals(1, again)
        compose.onNodeWithContentDescription("Helpful").assertDoesNotExist(); compose.onNodeWithTag("more_photos").assertDoesNotExist()
        compose.onAllNodesWithText("Removed \u2014 a source was forgotten").assertCountEquals(0)
    }

    @Test fun anAnswerAskedAgainShowsNoSecondButton() {
        show(forgottenAnswer("a4", com.rokid.context.memory.forget.AskSourceStates.reasked("a9")))
        compose.onNodeWithText("Answered from a forgotten fact \u2014 asked again").assertIsDisplayed()
        compose.onNodeWithTag("preview_ask_again").assertDoesNotExist()
    }

    // Nudge ------------------------------------------------------------------------------------------------------------------------------------------------------

    @Test fun aNudgeShowsTextWhyLiveSourcesOnlyAndTheActionCard() {
        show(load(RefKind.NUDGE, "n1"), MemoryDetailUiState(loading = false))
        compose.onNodeWithText("Milk is nearly out").assertIsDisplayed()
        compose.onNodeWithText("You saw an empty carton").assertIsDisplayed()
        compose.onNodeWithText("Typed note").assertIsDisplayed()
        compose.onAllNodesWithText("Hidden one").assertCountEquals(0)
        compose.onNodeWithText("proposal card for n1").performScrollTo().assertIsDisplayed()
    }
}
