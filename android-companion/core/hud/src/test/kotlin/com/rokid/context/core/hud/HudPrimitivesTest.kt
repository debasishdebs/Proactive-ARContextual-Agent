package com.rokid.context.core.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudPrimitivesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun scaleIsClampedToTheSupportedRange() {
        assertEquals(0.3f, clampHudScale(0.05f))
        assertEquals(1f, clampHudScale(4f))
        assertEquals(0.4f, clampHudScale(0.4f))
        assertEquals(0.4f, clampHudScale(Float.NaN), "NaN falls back to the default")
    }

    @Test fun labelAndToastRenderInsideScaledBox() {
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Scaled(scale = 9f, alignment = Alignment.BottomCenter) {
                    OutlinedBox(highlighted = true) { Label("NEXT 4:12", 20.sp, Hud.SECONDARY) }
                }
            }
            HudToast("Saved")
        }
        compose.onNodeWithText("NEXT 4:12").assertIsDisplayed()
        compose.onNodeWithText(" Saved").assertIsDisplayed()
    }
}
