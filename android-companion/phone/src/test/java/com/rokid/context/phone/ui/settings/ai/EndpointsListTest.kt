package com.rokid.context.phone.ui.settings.ai

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class EndpointsListTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun rowsShowKindModelsAndWhoUsesThem() = runBlocking {
        TestGraph.fresh(ctx)
        val ai = TestSettings.ai(ctx)
        ai.endpoints.save(Endpoint("az", "Work Azure", EndpointKind.AZURE_OPENAI, "https://az.example", null, listOf("gpt-5.4", "mini"), setOf(Capability.CHAT), 1), "k").getOrThrow()
        ai.bindings.set(RoleBinding(Role.ANSWER, "az", "gpt-5.4")).getOrThrow()
        val vm = EndpointsListViewModel(ai)
        val row = withTimeout(5_000) { vm.state.first { it.rows.isNotEmpty() } }.rows.single()
        assertEquals("Work Azure", row.name)
        assertEquals("Azure OpenAI · 2 models · used by Answer", row.subtitle)
        vm.cancelScope(); TestGraph.close(ctx)
    }

    @Test fun addOpensANewEditor() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { EndpointsListScreen(EndpointsListUiState(), onBack = {}, onOpen = { opened += it }) } }
        compose.onNodeWithText("No endpoints yet").assertExists()
        compose.onNodeWithTag("endpoint_add").performClick()
        assertEquals(listOf(Routes.NEW_ENDPOINT), opened)
    }
}
