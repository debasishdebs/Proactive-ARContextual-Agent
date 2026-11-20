package com.rokid.context.phone.ui.settings.modelprivacy

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.resolve.ModelPrivacyStore
import com.rokid.context.core.net.ModelPrivacy
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P2-1 UI path: a tap on Offline only is in force at once and survives the screen being left right away. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ModelPrivacyControlTest {
    @get:Rule val compose = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs(n: String) = ctx.getSharedPreferences(n, Context.MODE_PRIVATE).also { it.edit().clear().commit() }

    @Test fun setModeFollowedByCloseStillChangesTheStoreAtOnceAndTheDurableWriteStillCompletes() {
        val p = prefs("mpc"); val s = prefs("mpcs")
        val store = ModelPrivacyStore(p, s)
        store.warm()
        val control = AndroidModelPrivacyControl(ctx, store) { null }
        control.setMode(ModelPrivacy.OFFLINE_ONLY)
        control.close() // the screen is left right after the tap
        assertEquals("the router and the guards read this", ModelPrivacy.OFFLINE_ONLY, store.current())
        val end = System.currentTimeMillis() + 5_000
        while (control.state.value.saving && System.currentTimeMillis() < end) Thread.sleep(20)
        assertFalse(control.state.value.saving); assertFalse(control.state.value.saveFailed)
        assertEquals("durable although the control's scope was cancelled", ModelPrivacy.OFFLINE_ONLY, ModelPrivacyStore(p, s).current())
    }

    @Test fun aFailedWriteIsShownAndTheScreenKeepsTheModeInMemory() {
        compose.setContent { PcTheme { ModelPrivacyScreen(ModelPrivacyUiState(ModelPrivacy.OFFLINE_ONLY, saveFailed = true), {}, {}) } }
        compose.onNodeWithTag("model_privacy_save_failed").assertTextContains("may not survive a restart", substring = true)
        assertTrue(ModelPrivacyModel.SAVE_FAILED.startsWith("Couldn't save"))
        compose.onNodeWithTag("model_privacy_mode").assertTextContains("Offline only", substring = true)
    }
}
