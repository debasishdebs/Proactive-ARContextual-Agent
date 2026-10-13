package com.rokid.context.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CodeBoxTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editableBoxTakesInput() {
        compose.setContent { PcTheme { var t by remember { mutableStateOf("{") }; CodeBox(t, { t = it }, label = "Output schema") } }
        compose.onNodeWithText("Output schema").assertExists()
        compose.onNodeWithTag("code_box").performTextInput("}")
        compose.onNodeWithTag("code_box").assertTextEquals("{}")
    }

    @Test fun readOnlyBoxShowsTextOnly() {
        compose.setContent { PcTheme { CodeBox("{\"type\":\"object\"}", null, tag = "wire") } }
        compose.onNodeWithTag("wire").assertExists()
        compose.onNodeWithText("{\"type\":\"object\"}").assertExists()
    }

    @Test fun delayedParentLosesNoKeystrokes() {
        var t by mutableStateOf("")
        val pending = mutableListOf<String>()
        compose.setContent { PcTheme { CodeBox(t, { pending += it }) } }
        for (c in "abc") compose.onNodeWithTag("code_box").performTextInput(c.toString())
        // the parent has applied nothing yet; the field must still hold everything typed
        compose.onNodeWithTag("code_box").assertTextEquals("abc")
        t = pending.last()
        compose.waitForIdle()
        compose.onNodeWithTag("code_box").assertTextEquals("abc")
    }

    @Test fun externalChangeReplacesTextAndCaretGoesToEnd() {
        var t by mutableStateOf("old")
        compose.setContent { PcTheme { CodeBox(t, { t = it }) } }
        t = "restored"
        compose.waitForIdle()
        compose.onNodeWithTag("code_box").assertTextEquals("restored")
        compose.onNodeWithTag("code_box").performTextInput("!")
        compose.onNodeWithTag("code_box").assertTextEquals("restored!")
    }

    @Test fun caretSurvivesUnrelatedRecomposition() {
        var t by mutableStateOf("ab")
        var tick by mutableStateOf(0)
        compose.setContent { PcTheme { tick; CodeBox(t, { t = it }) } }
        compose.onNodeWithTag("code_box").performTextInput("c")
        compose.onNodeWithTag("code_box").performTextInput("d")
        tick++
        compose.waitForIdle()
        compose.onNodeWithTag("code_box").performTextInput("e")
        compose.onNodeWithTag("code_box").assertTextEquals("abcde")
    }

    @Test fun laggingEchoesOfQuickTypingLoseNothing() {
        var t by mutableStateOf("")
        compose.setContent { PcTheme { CodeBox(t, { }) } }
        for (c in "abc") compose.onNodeWithTag("code_box").performTextInput(c.toString())
        t = "a"; compose.waitForIdle()
        compose.onNodeWithTag("code_box").assertTextEquals("abc")
        compose.onNodeWithTag("code_box").performTextInput("d")
        for (v in listOf("ab", "abc", "abcd")) { t = v; compose.waitForIdle() }
        compose.onNodeWithTag("code_box").assertTextEquals("abcd")
        compose.onNodeWithTag("code_box").performTextInput("e")
        compose.onNodeWithTag("code_box").assertTextEquals("abcde")
    }

    @Test fun rejectedEditResyncsToTheParentValue() {
        var t by mutableStateOf("")
        compose.setContent { PcTheme { CodeBox(t, { }) } }
        compose.onNodeWithTag("code_box").performTextInput("x")
        t = "x"; compose.waitForIdle()
        t = ""; compose.waitForIdle()
        compose.onNodeWithTag("code_box").assertTextEquals("")
    }

    /**
     * Phase 5 T15 carry, fix round 1: a parent that echoes only the newest text can leave older duplicates in the
     * record (from values alone that is the same as a parent still echoing in order, see the next test), but they
     * are gone once the next edit is echoed, so they can't mask a later restore.
     */
    @Test fun aConflatedEchoLeavesNothingStaleOnceTheNextEditIsEchoed() {
        var t by mutableStateOf("")
        compose.setContent { PcTheme { CodeBox(t, { }) } }
        compose.onNodeWithTag("code_box").performTextInput("a")
        compose.onNodeWithTag("code_box").performTextInput("b")
        compose.onNodeWithTag("code_box").performTextReplacement("a") // emitted: a, ab, a
        t = "a"; compose.waitForIdle() // the parent conflated to the newest text
        compose.onNodeWithTag("code_box").performTextInput("c")
        t = "ac"; compose.waitForIdle() // the next edit is echoed
        t = "ab"; compose.waitForIdle() // then an external restore to an earlier text
        compose.onNodeWithTag("code_box").assertTextEquals("ab")
    }

    /** Fix round 1: a parent that echoes every value in order (a, ab, a) never overwrites the newer field text. */
    @Test fun aSequentialEchoOfEveryValueLosesNothing() {
        var t by mutableStateOf("")
        compose.setContent { PcTheme { CodeBox(t, { }) } }
        compose.onNodeWithTag("code_box").performTextInput("a")
        compose.onNodeWithTag("code_box").performTextInput("b")
        compose.onNodeWithTag("code_box").performTextReplacement("a") // emitted: a, ab, a
        for (v in listOf("a", "ab")) { t = v; compose.waitForIdle(); compose.onNodeWithTag("code_box").assertTextEquals("a") }
        t = "a"; compose.waitForIdle()
        compose.onNodeWithTag("code_box").assertTextEquals("a")
        compose.onNodeWithTag("code_box").performTextInput("c")
        compose.onNodeWithTag("code_box").assertTextEquals("ac")
    }
}
