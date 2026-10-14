package com.rokid.context.phone.ui.settings.ai

import com.rokid.context.ai.local.LocalModel
import com.rokid.context.ai.local.LocalModels
import com.rokid.context.data.models.LocalModelState
import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.ProviderConfigException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.list.ActionResult
import com.rokid.context.phone.ui.list.ItemStatus
import com.rokid.context.phone.ui.settings.SettingsRootScreen
import com.rokid.context.phone.ui.settings.SettingsRootUiState
import com.rokid.context.phone.ui.settings.TestSettings
import com.rokid.context.phone.ui.shell.Routes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Records every store call; [onDelete] runs inside delete(), so a test can look at the world at that moment. */
private class FakeGateway(private val dir: File) : LocalModelGateway {
    val events = mutableListOf<String>()
    val states = mutableMapOf<String, MutableStateFlow<LocalModelState>>()
    var onDelete: () -> Unit = {}
    var fileName: (LocalModel) -> String = { it.file }

    fun flow(m: LocalModel) = states.getOrPut(m.id) { MutableStateFlow(LocalModelState.Absent) }
    fun set(m: LocalModel, s: LocalModelState) { flow(m).value = s; if (s == LocalModelState.Ready) fileFor(m).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) } }

    override fun fileFor(model: LocalModel) = File(dir, fileName(model))
    override fun state(model: LocalModel): Flow<LocalModelState> = flow(model)
    override fun enqueue(model: LocalModel, allowMetered: Boolean) { events += "enqueue:${model.id}:metered=$allowMetered"; flow(model).value = LocalModelState.Downloading(0f) }
    override fun setAllowMetered(model: LocalModel, allowMetered: Boolean) { events += "network:${model.id}:metered=$allowMetered" }
    override fun cancel(model: LocalModel) { events += "cancel:${model.id}"; flow(model).value = LocalModelState.Absent }
    override fun delete(model: LocalModel) { onDelete(); events += "delete:${model.id}"; fileFor(model).delete(); flow(model).value = LocalModelState.Absent }
}

private class FakeMobileData(var on: Boolean = false) : MobileDataSetting {
    override fun allowed() = on
    override fun set(allowed: Boolean) { on = allowed }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LocalModelsPageTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val bge = LocalModels.byId("bge-small-en-v1.5-q8")!!
    private val endpointId = LocalModelsSource.endpointIdFor(bge)
    private lateinit var ai: AiGraph
    private lateinit var gateway: FakeGateway
    private val mobile = FakeMobileData()
    private var reindexes = 0

    @Before fun setUp() {
        TestGraph.fresh(ctx); ai = TestSettings.ai(ctx)
        gateway = FakeGateway(File(ctx.filesDir, "models_test").apply { deleteRecursively() })
    }

    @After fun tearDown() = TestGraph.close(ctx)

    private fun source(abi: Boolean = true, failures: LoadFailures = LoadFailures.None) =
        LocalModelsSource(listOf(bge), gateway, ai, { reindexes++ }, mobile, abiSupported = abi, loadFailures = failures)

    private fun row(s: LocalModelsSource) = runBlocking { s.load() }.single()

    private fun show(s: LocalModelsSource) {
        compose.setContent { PcTheme { LocalModelsRoute(s, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(bge.title)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun texts(n: SemanticsNode): List<String> = buildList {
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
        n.children.forEach { addAll(texts(it)) }
    }

    @Test fun rowsShowSizeLanguagesRamAndState() = runBlocking {
        val s = source()
        row(s).let {
            assertEquals("bge-small (English)", it.title)
            assertEquals("37 MB \u00b7 English \u00b7 ~90 MB RAM", it.subtitle)
            assertEquals("Download", it.trailingText)
            assertEquals(ItemStatus.ActionNeeded, it.status)
        }
        gateway.set(bge, LocalModelState.Downloading(0.42f))
        row(s).let { assertEquals("Downloading 42 %", it.trailingText); assertEquals(ItemStatus.InProgress, it.status) }
        gateway.set(bge, LocalModelState.Ready)
        row(s).let { assertEquals("Downloaded", it.trailingText); assertEquals(ItemStatus.Done, it.status) }
        s.useForEmbeddings(bge)
        row(s).let { assertEquals("In use", it.trailingText); assertEquals(ItemStatus.Done, it.status) }
        gateway.set(bge, LocalModelState.Failed("Download didn't verify \u2014 try again"))
        assertEquals("Download didn't verify \u2014 try again", row(s).trailingText)
        show(s)
        compose.onNodeWithText("37 MB \u00b7 English \u00b7 ~90 MB RAM").assertExists()
        Unit
    }

    @Test fun downloadConfirmShowsSizeAndRam() {
        val s = source()
        show(s)
        compose.onNodeWithText("Downloads use Wi-Fi only", substring = true).assertExists()
        compose.onNodeWithTag("list_row_${bge.id}").performClick()
        compose.onNodeWithText("37 MB download over Wi-Fi", substring = true).assertExists()
        compose.onNodeWithText("~90 MB of RAM", substring = true).assertExists()
        compose.onNodeWithTag("list_confirm").performClick()
        compose.waitUntil(5_000) { gateway.events.isNotEmpty() }
        assertEquals(listOf("enqueue:${bge.id}:metered=false"), gateway.events)
        compose.onNodeWithTag("list_progress_cancel").assertExists()
        gateway.set(bge, LocalModelState.Ready)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Downloaded")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun mobileDataSwitchAllowsMeteredDownload() {
        val s = source()
        show(s)
        compose.onNodeWithTag("local_models_mobile_data").performClick()
        compose.waitForIdle()
        assertTrue(mobile.on)
        compose.onNodeWithTag("list_row_${bge.id}").performClick()
        compose.onNodeWithTag("list_confirm").performClick()
        compose.waitUntil(5_000) { gateway.events.isNotEmpty() }
        assertEquals(listOf("enqueue:${bge.id}:metered=true"), gateway.events)
        compose.onNodeWithTag("list_progress_cancel").performClick()
        compose.waitUntil(5_000) { "cancel:${bge.id}" in gateway.events }
    }

    /** Final review I3: flipping the switch reaches a download already queued or running, both ways; other rows are untouched. */
    @Test fun mobileDataSwitchReachesARunningDownload() = runBlocking {
        val s = source()
        gateway.set(bge, LocalModelState.Downloading(0.4f))
        s.load()
        s.setAllowMetered(true)
        s.setAllowMetered(false)
        assertEquals(listOf("network:${bge.id}:metered=true", "network:${bge.id}:metered=false"), gateway.events)
        assertEquals(false, mobile.on)

        gateway.events.clear()
        gateway.set(bge, LocalModelState.Ready)
        s.load()
        s.setAllowMetered(true)
        assertTrue("a downloaded model has no download to change", gateway.events.isEmpty())
    }

    @Test fun useForEmbeddingsCreatesEndpointAndBindsEmbed() = runBlocking {
        gateway.set(bge, LocalModelState.Ready)
        val s = source()
        val action = s.actionFor(row(s))!!
        assertEquals("Use for Embeddings", action.confirmLabel)
        assertTrue(action.run {} !is ActionResult.Failed)
        assertEquals("local_bge-small-en-v1_5-q8", endpointId)
        val e = ai.endpoints.byId(endpointId)!!
        assertEquals("On-device \u00b7 bge-small (English)", e.name)
        assertEquals(EndpointKind.LLAMACPP_LOCAL, e.kind)
        assertEquals(gateway.fileFor(bge).absolutePath, e.baseUrl)
        assertEquals(listOf(bge.id), e.models)
        assertEquals(setOf(Capability.EMBEDDINGS), e.capabilities)
        val b = ai.bindings.get(Role.EMBED)!!
        assertEquals(endpointId, b.endpointId)
        assertEquals(bge.id, b.model)
        assertEquals(1, reindexes)
    }

    @Test fun rebindDuringReindexStartsANewGeneration() = runBlocking {
        // EMBED already on a cloud endpoint, its reindex still running (the callback only enqueues the swap).
        ai.endpoints.save(Endpoint("ol", "Ollama", EndpointKind.OLLAMA, "http://localhost:11434", null, listOf("nomic-embed-text"), setOf(Capability.EMBEDDINGS), 2), null).getOrThrow()
        ai.bindings.set(RoleBinding(Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
        gateway.set(bge, LocalModelState.Ready)
        val s = source()
        s.useForEmbeddings(bge)
        assertEquals(endpointId, ai.bindings.get(Role.EMBED)!!.endpointId)
        assertEquals(1, reindexes)
        s.useForEmbeddings(bge)
        assertEquals("the same binding again is no change: no new generation", 1, reindexes)
        gateway.fileName = { "moved_${it.file}" }
        gateway.set(bge, LocalModelState.Ready)
        s.useForEmbeddings(bge)
        assertEquals("a new model file is a new space", 2, reindexes)
        assertEquals(gateway.fileFor(bge).absolutePath, ai.endpoints.byId(endpointId)!!.baseUrl)
    }

    @Test fun deleteWhileBoundUnbindsThenDeletes() = runBlocking {
        gateway.set(bge, LocalModelState.Ready)
        val s = source()
        s.useForEmbeddings(bge)
        val file = gateway.fileFor(bge)
        var boundAtDelete: RoleBinding? = null
        var endpointAtDelete: Endpoint? = null
        gateway.onDelete = { runBlocking { boundAtDelete = ai.bindings.get(Role.EMBED); endpointAtDelete = ai.endpoints.byId(endpointId) } }
        val action = s.actionFor(row(s))!!
        assertEquals("Delete", action.confirmLabel)
        assertTrue(action.run {} !is ActionResult.Failed)
        assertNull("EMBED is unbound before the file goes", boundAtDelete)
        assertNull("the endpoint is gone before the file goes", endpointAtDelete)
        assertEquals(listOf("delete:${bge.id}"), gateway.events)
        assertFalse(file.exists())
        assertNull(ai.bindings.get(Role.EMBED))
        assertEquals("bind + unbind", 2, reindexes)
        assertEquals("Download", row(s).trailingText)
    }

    @Test fun deleteWhileBoundWarnsKeywordsOnly() {
        gateway.set(bge, LocalModelState.Ready)
        val s = source()
        val unbound = s.moreActionsFor(row(s)).single()
        assertEquals("Delete", unbound.label)
        assertFalse(unbound.action.confirmMessage.contains("keywords only"))
        runBlocking { s.useForEmbeddings(bge) }
        show(s)
        compose.onNodeWithText("In use").assertExists()
        compose.onNodeWithTag("list_row_${bge.id}").performClick()
        compose.onNodeWithText("Search will use keywords only until you pick another embeddings model", substring = true).assertExists()
    }

    @Test fun nonArm64DisablesDownload() {
        val s = source(abi = false)
        assertNull(s.actionFor(row(s)))
        show(s)
        compose.onNodeWithText("On-device models need a 64-bit ARM phone").assertExists()
        compose.onNodeWithTag("list_row_${bge.id}").performClick()
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Download?", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(gateway.events.isEmpty())
    }

    @Test fun loadFailureBannerIsUserSafe() {
        gateway.set(bge, LocalModelState.Ready)
        val raw = ProviderConfigException("llama.cpp native runtime not available in current APK build")
        val s = source(failures = { id -> if (id == bge.id) LoadFailure(2, raw) else null })
        assertEquals("Couldn't load \u2014 re-download", row(s).trailingText)
        show(s)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("On-device model couldn't load", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        val shown = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::texts)
        assertTrue(shown.toString(), shown.none { it.contains("llama.cpp") || it.contains("Exception") || it.contains("APK") })
    }

    /** Spec §F.2: Roll back appears only while a retired index is inside its 7-day grace. */
    @Test fun rollBackShownOnlyInGrace() {
        val graph = TestGraph.fresh(ctx)
        var t = 1_000L
        val day = 24 * 60 * 60 * 1000L
        val spaces = com.rokid.context.memory.EmbeddingSpaces(graph.db.embeddingDao(), com.rokid.context.memory.InMemoryRetiredClock(), { t })
        runBlocking {
            graph.memories.add(com.rokid.context.memory.db.MemoryEntity("m1", null, "t", "note", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, 1, false, ""))
            val a = spaces.startBuilding("fpA", "{}", 2, "[]", 1); spaces.put("m1", a.id, floatArrayOf(1f, 0f)); spaces.swapIfComplete(a.id)
            val b = spaces.startBuilding("fpB", "{}", 2, "[]", 2); spaces.put("m1", b.id, floatArrayOf(1f, 0f)); spaces.swapIfComplete(b.id)
        }
        t += 3 * day
        val inGrace = LocalModelsSource(listOf(bge), gateway, ai, {}, mobile, abiSupported = true, swap = SpacesSwapRollback(spaces) {})
        show(inGrace)
        compose.onNodeWithTag("local_models_roll_back").assertExists()
        compose.onNodeWithTag("local_models_roll_back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Rolled back", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("local_models_roll_back").assertDoesNotExist()
    }

    @Test fun rollBackHiddenAfterGraceAndDialogCarriesTheCounts() {
        val graph = TestGraph.fresh(ctx)
        var t = 1_000L
        val spaces = com.rokid.context.memory.EmbeddingSpaces(graph.db.embeddingDao(), com.rokid.context.memory.InMemoryRetiredClock(), { t })
        runBlocking {
            graph.memories.add(com.rokid.context.memory.db.MemoryEntity("m1", null, "t", "note", "", com.rokid.context.memory.db.MemoryTier.SHORT, 0.8, com.rokid.context.memory.db.ReviewState.OK, false, 1, false, ""))
            val a = spaces.startBuilding("fpA", "{}", 2, "[]", 1); spaces.put("m1", a.id, floatArrayOf(1f, 0f)); spaces.swapIfComplete(a.id)
            val b = spaces.startBuilding("fpB", "{}", 2, "[]", 2); spaces.put("m1", b.id, floatArrayOf(1f, 0f)); spaces.swapIfComplete(b.id)
        }
        t += 8 * 24 * 60 * 60 * 1000L
        show(LocalModelsSource(listOf(bge), gateway, ai, {}, mobile, abiSupported = true, swap = SpacesSwapRollback(spaces) {}))
        compose.onNodeWithTag("local_models_roll_back").assertDoesNotExist()
        assertEquals("2 photos will be searchable by text only\n1 photos will no longer be findable at all\nBoth can be recovered with Roll back for 7 days.",
            LocalModelsSource.impactText(com.rokid.context.memory.SwapImpact(2, 1)).trim())
        assertEquals("", LocalModelsSource.impactText(com.rokid.context.memory.SwapImpact(0, 0)))
    }

    @Test fun settingsAiRowSitsAfterAgentFlows() {
        val opened = mutableListOf<String>()
        compose.setContent { PcTheme { SettingsRootScreen(SettingsRootUiState("Linked", "Every 5 min"), live = Routes.LIVE_SETTINGS, onOpen = { opened += it }) } }
        compose.onNodeWithTag("settings_list").performScrollToNode(hasText("On-device models"))
        compose.onNodeWithText("Run search models on your phone").assertExists()
        val flows = compose.onNodeWithText("Agent flows").getUnclippedBoundsInRoot().top
        val local = compose.onNodeWithText("On-device models").getUnclippedBoundsInRoot().top
        val endpoints = compose.onNodeWithText("Model endpoints").getUnclippedBoundsInRoot().top
        assertTrue(local > flows && local < endpoints)
        compose.onNodeWithText("On-device models").performClick()
        assertEquals(listOf(Routes.SETTINGS_LOCAL_MODELS), opened)
    }
}
