package com.rokid.context.phone.ui.memory

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryDetailScreenTest {
    @get:Rule val compose = createComposeRule()
    private val base = MemoryDetailUiState(loading = false, found = true, text = "Keys on shelf", tierLabel = "Short-term", reviewLabel = "Reviewed")

    @Test fun forgetMomentHiddenWithoutFrame() {
        compose.setContent { PcTheme { MemoryDetailScreen(base, {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Forget fact").assertIsEnabled()
        compose.onNodeWithText("Forget moment").assertDoesNotExist()
    }

    @Test fun forgetMomentShownWithFrame() {
        var asked: ForgetKind? = null
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1"), {}, {}, {}, {}, {}, {}, { _, _ -> }, onForget = { asked = it }) } }
        compose.onNodeWithText("Forget moment").performClick()
        assertEquals(ForgetKind.MOMENT, asked)
    }

    @Test fun forgetFactDialogCopy() {
        var confirmed = 0
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(confirm = ForgetKind.FACT), {}, {}, {}, {}, {}, {}, { _, _ -> }, onConfirmForget = { confirmed++ }) } }
        compose.onNodeWithText("Forget this fact?").assertExists()
        compose.onNodeWithText("It's removed everywhere, and Proactive Context won't learn it from this photo again. Answers that used it will show as removed.").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
        compose.onNodeWithText("Forget").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun forgetMomentDialogCopy() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Forget this moment?").assertExists()
        compose.onNodeWithText("The photo and every fact from it are deleted. Answers that used them will show as removed.").assertExists()
    }

    @Test fun buttonsDisabledWhileRunningAndForHiddenMemories() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", forgetting = true), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Forget fact").assertIsNotEnabled()
        compose.onNodeWithText("Forget moment").assertIsNotEnabled()
    }
    /** Device check D-E1: in a short viewport (landscape) the photo fills the screen; a one-finger drag on it must scroll the page. */
    @Test fun dragOnUnzoomedPhotoScrollsThePage() {
        compose.setContent { PcTheme { Box(Modifier.height(360.dp)) { MemoryDetailScreen(base.copy(frameId = "f1", framePath = "/missing.jpg"), {}, {}, {}, {}, {}, {}, { _, _ -> }) } } }
        compose.onNodeWithText("Photo no longer on this phone").performTouchInput { swipe(start = center, end = center - Offset(0f, 600f), durationMillis = 300) }
        compose.waitForIdle()
        compose.onNodeWithText("Forget fact").assertIsDisplayed()
    }

    // ---- v0.9.7 slice C: the dialog names the scope in plain words and the counts; Forget stays disabled until they are shown ----
    private val scene = ForgetPreview(com.rokid.context.memory.forget.ForgetScope.SCENE, com.rokid.context.memory.forget.ForgetScope.SCENE, 4, 5, 1, 3, 1, 2)

    @Test fun theDialogStatesTheScopeInPlainWordsAndTheCounts() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreview = scene, forgetConfirmable = true), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText(ForgetScopeCopy.dialogScope(com.rokid.context.memory.forget.ForgetScope.SCENE)).assertExists()
        compose.onNodeWithText("This deletes 4 photos, 5 facts, 1 mark, 3 Moments. 1 Scene removed, 2 Scenes shrink.").assertExists()
        compose.onNodeWithText("Forget").assertIsEnabled()
    }

    @Test fun theDialogShowsTheEffectiveScopeNotTheSetting() {
        val p = ForgetPreview(com.rokid.context.memory.forget.ForgetScope.SCENE, com.rokid.context.memory.forget.ForgetScope.MOMENT, 1, 1, 0, 1, 0, 0)
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreview = p, forgetConfirmable = true), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText(ForgetScopeCopy.dialogScope(com.rokid.context.memory.forget.ForgetScope.MOMENT)).assertExists()
        compose.onNodeWithText("This deletes 1 photo, 1 fact, 0 marks, 1 Moment.").assertExists()
    }

    @Test fun whileCountingTheDialogSaysSoAndForgetIsDisabled() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreviewing = true), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText(ForgetScopeCopy.COUNTING).assertExists(); compose.onNodeWithText("Forget").assertIsNotEnabled()
    }

    @Test fun aFailedCountSaysSoAndForgetIsDisabledWhenBlocked() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreviewFailed = true, forgetConfirmable = false), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText(ForgetScopeCopy.COUNTS_FAILED).assertExists(); compose.onNodeWithText("Forget").assertIsNotEnabled()
    }

    @Test fun theCopyIsPlainAndDistinct() {
        val all = com.rokid.context.memory.forget.ForgetScope.values()
        assertEquals(3, all.map { ForgetScopeCopy.dialogScope(it) }.distinct().size); assertEquals(listOf("Photo", "Moment", "Scene"), all.map { ForgetScopeCopy.label(it) })
        assertEquals("This deletes 1 photo, 1 fact, 1 mark, 1 Moment.", ForgetScopeCopy.counts(1, 1, 1, 1, 0, 0))
        assertEquals("This deletes 0 photos, 2 facts, 0 marks, 0 Moments. 2 Scenes removed.", ForgetScopeCopy.counts(0, 2, 0, 0, 2, 0))
        assertEquals("This deletes 1 photo, 1 fact, 0 marks, 1 Moment. 1 Scene shrinks.", ForgetScopeCopy.counts(1, 1, 0, 1, 0, 1))
    }

    @Test fun theDialogShowsTheContentChangedMessage() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreview = scene, forgetConfirmable = true, message = ForgetCopy.CONTENT_CHANGED), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        assertEquals("the page and the dialog both show it", 2, compose.onAllNodesWithText("Content changed since the preview. Review again.").fetchSemanticsNodes().size)
    }

    @Test fun theDialogSaysHowManyItemsOutsideTheWindowAreAffectedButNotDeleted() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreview = scene.copy(outside = 3), forgetConfirmable = true), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("3 items outside this window are affected (not deleted).").assertExists()
        assertEquals("1 item outside this window is affected (not deleted).", ForgetScopeCopy.outsideLine(1))
    }

    @Test fun noOutsideLineWhenNothingOutsideIsAffected() {
        compose.setContent { PcTheme { MemoryDetailScreen(base.copy(frameId = "f1", confirm = ForgetKind.MOMENT, forgetPreview = scene, forgetConfirmable = true), {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("0 items outside this window are affected (not deleted).").assertDoesNotExist()
    }
}
