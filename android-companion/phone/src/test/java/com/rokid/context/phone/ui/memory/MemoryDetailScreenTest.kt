package com.rokid.context.phone.ui.memory

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
}
