package com.rokid.context.connectors.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.ActionsGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class McpConnectorTest {
    @Test fun notRegisteredAndEmpty() = runBlocking {
        assertTrue(ActionsGraph.get(ApplicationProvider.getApplicationContext<Context>()).tools.isEmpty())
        assertTrue(McpConnector().listTools().isEmpty())
        assertFailsWith<UnsupportedOperationException> { McpConnector().invoke("x", JsonObject(emptyMap())) }
        Unit
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionsGraphReadyTest {
    /** Fix round 1: screens wait on `ready`, which is published only after the settings prefs were read off Main. */
    @Test fun readyPublishesOnceSettingsAreLoaded() = runBlocking {
        val g = kotlinx.coroutines.withTimeout(5_000) {
            ActionsGraph.get(ApplicationProvider.getApplicationContext<Context>())
            ActionsGraph.ready.first { it != null }
        }!!
        assertTrue(g.settings.settings.value.isNotEmpty())
    }
}
