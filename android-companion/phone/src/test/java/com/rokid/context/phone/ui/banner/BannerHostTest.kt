package com.rokid.context.phone.ui.banner

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.theme.PcTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BannerHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsTheBannerAndReportsItsAction() {
        var got: BannerAction? = null
        compose.setContent { PcTheme { BannerHost(BannerResolver.resolve(BannerInputs(link = LinkState.OFFLINE)), onAction = { got = it }) } }
        compose.onNodeWithText("Glasses disconnected").assertIsDisplayed()
        compose.onNodeWithText("Reconnect").performClick()
        assertEquals(BannerAction.RECONNECT, got)
    }
}
