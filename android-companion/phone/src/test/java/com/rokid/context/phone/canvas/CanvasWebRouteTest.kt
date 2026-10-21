package com.rokid.context.phone.canvas

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.canvas.ui.CanvasWebRoute
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CanvasWebRouteTest {
    @get:Rule val rule = createComposeRule()

    @Test fun openingTheScreenDoesNotStartTheServerItself() {
        val host = FakeCanvasHost()
        rule.setContent { CanvasWebRoute(host, onBack = {}) }
        rule.waitForIdle()
        assertFalse("only the service starts the server: ${host.calls}", host.calls.any { it.startsWith("start") })
        assertFalse(host.status.value.on)
    }
}
