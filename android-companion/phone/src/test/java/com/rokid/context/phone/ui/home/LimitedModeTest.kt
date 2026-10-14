package com.rokid.context.phone.ui.home

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.sources.SourceKind
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** G3 (v1 section 0.1, MVP section 12.5): one dismissible "Some features are limited" banner when optional permissions were skipped. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LimitedModeTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val all = setOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CALENDAR,
        Manifest.permission.READ_CONTACTS, Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private fun missing(granted: Set<String>, sources: Set<SourceKind> = emptySet(), sdk: Int = 34) =
        LimitedMode.missing({ it in granted }, sdk, sources)

    @Test fun skippedMicOrNotificationsIsLimited() {
        assertEquals(emptySet<String>(), missing(all))
        assertEquals(setOf(LimitedMode.MIC), missing(all - Manifest.permission.RECORD_AUDIO))
        assertEquals(setOf(LimitedMode.NOTIFICATIONS), missing(all - Manifest.permission.POST_NOTIFICATIONS))
        assertEquals("no runtime grant before Android 13", emptySet<String>(), missing(all - Manifest.permission.POST_NOTIFICATIONS, sdk = 32))
    }

    @Test fun aSourceThatIsOnWithoutItsPermissionIsLimited() {
        val granted = setOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("sources that are off ask for nothing", emptySet<String>(), missing(granted))
        assertEquals(
            setOf(LimitedMode.CALENDAR, LimitedMode.LOCATION, LimitedMode.CONTACTS),
            missing(granted, setOf(SourceKind.CALENDAR, SourceKind.LOCATION, SourceKind.CONTACTS, SourceKind.NOTES)),
        )
    }

    @Test fun dismissHidesTheBannerUntilSomethingElseIsMissing() = runBlocking {
        val prefs = app.getSharedPreferences("limited_mode_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        var current = setOf(LimitedMode.MIC)
        val control = PrefsLimitedMode(prefs) { current }
        control.refresh()
        assertTrue(control.show.value)
        control.dismiss()
        assertFalse(control.show.value)
        control.refresh()
        assertFalse("still dismissed", PrefsLimitedMode(prefs) { current }.also { it.refresh() }.show.value)
        current = setOf(LimitedMode.MIC, LimitedMode.NOTIFICATIONS)
        control.refresh()
        assertTrue("a newly skipped permission shows it again", control.show.value)
        current = emptySet()
        control.refresh()
        assertFalse(control.show.value)
    }

    @Test fun homeShowsTheLimitedBannerWithReviewAndDismiss() {
        var review = 0
        var dismiss = 0
        compose.setContent {
            PcTheme {
                HomeScreen(
                    HomeUiState(status = StatusUi.Initial, limited = true), onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {},
                    onBannerAction = {}, onOpenAppSettings = { review++ }, onDismissLimited = { dismiss++ },
                )
            }
        }
        compose.onNodeWithText(LimitedMode.TITLE).assertExists()
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        assertEquals(1, review)
        assertEquals(1, dismiss)
    }

    @Test fun noBannerWhenNothingIsLimited() {
        compose.setContent {
            PcTheme { HomeScreen(HomeUiState(status = StatusUi.Initial), onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {}) }
        }
        compose.onNodeWithText(LimitedMode.TITLE).assertDoesNotExist()
    }
}
