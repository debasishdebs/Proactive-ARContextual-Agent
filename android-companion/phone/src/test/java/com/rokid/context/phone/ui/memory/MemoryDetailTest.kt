package com.rokid.context.phone.ui.memory

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryDetailTest {
    private val main = MainDispatcherRule()
    private val compose = createComposeRule()
    /** Explicit order: Main is installed before, and reset after, the Compose rule (unordered @Rules raced on Dispatchers.setMain). */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(main).around(compose)
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<MemoryDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(id: String) = MemoryDetailViewModel(id, graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }).also { vms += it }

    @Test fun modelLabelsAndFrameDate() {
        val m = Fixtures.memory("m1", "Keys on shelf", Fixtures.at(TODAY, 8, 5), frameId = "f1", tags = "keys, hallway", review = ReviewState.NEEDS_REVIEW)
            .copy(tier = MemoryTier.LONG)
        val s = MemoryDetailModel.build(m, Fixtures.frame("f1", Fixtures.at(TODAY, 8), path = "/f1.jpg"), Fixtures.ZONE)
        assertEquals(listOf("keys", "hallway"), s.tags)
        assertEquals("Long-term", s.tierLabel)
        assertEquals("Needs review", s.reviewLabel)
        assertEquals("Mon 12 Oct · 08:05", s.createdLabel)
        assertEquals(TODAY, s.frameDate)
        assertEquals("/f1.jpg", s.framePath)
        assertEquals(1f, Zoom.clamp(0.2f)); assertEquals(5f, Zoom.clamp(9f)); assertEquals(1f, Zoom.clamp(Float.NaN)); assertEquals(2.5f, Zoom.clamp(2.5f))
    }

    @Test fun trustPersists() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm("m1")
        withTimeout(5_000) { model.state.first { it.found } }
        model.trust()
        val s = withTimeout(5_000) { model.state.first { it.trusted && it.message != null } }
        assertEquals("Marked as trusted", s.message)
    }

    @Test fun editSavesReindexesAndRejectsBlank() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Milk carton nearly empty", 1))
        val model = vm("m1")
        withTimeout(5_000) { model.state.first { it.found } }
        model.startEdit()
        assertEquals("Milk carton nearly empty", model.state.value.draft)
        model.updateDraft("   ")
        model.saveEdit()
        assertEquals("Text can't be empty", withTimeout(5_000) { model.state.first { it.message != null } }.message)
        model.updateDraft("Oat milk bought")
        model.saveEdit()
        val s = withTimeout(5_000) { model.state.first { it.text == "Oat milk bought" && it.draft == null } }
        assertEquals("Saved", s.message)
        assertEquals(listOf("m1"), graph.memories.search("oat", 5).map { it.id })
    }

    @Test fun deletedMemoryShowsNotFound(): Unit = runBlocking {
        assertFalse(withTimeout(5_000) { vm("gone").state.first { !it.loading } }.found)
        compose.setContent { PcTheme { MemoryDetailScreen(MemoryDetailModel.NOT_FOUND, {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("This memory is no longer available").assertIsDisplayed()
    }

    @Test fun screenActionsAndDisabledForget() {
        var trusts = 0; var edits = 0
        val state = MemoryDetailUiState(loading = false, found = true, text = "Keys on shelf", tierLabel = "Short-term", confidence = 0.71, reviewLabel = "Reviewed", createdLabel = "Mon 12 Oct · 08:05")
        compose.setContent { PcTheme { MemoryDetailScreen(state, {}, { trusts++ }, { edits++ }, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Keys on shelf").assertIsDisplayed()
        compose.onNodeWithText("71%").assertIsDisplayed()
        compose.onNodeWithText("Trust").performClick()
        compose.onNodeWithText("Edit").performClick()
        assertEquals(1, trusts); assertEquals(1, edits)
        compose.onNodeWithText("Forget fact").assertIsNotEnabled()
        compose.onNodeWithText("Forget moment").assertIsNotEnabled()
    }

    @Test fun editingShowsSaveAndCancel() {
        var saved = 0
        val state = MemoryDetailUiState(loading = false, found = true, text = "Keys", draft = "Keys on hallway shelf")
        compose.setContent { PcTheme { MemoryDetailScreen(state, {}, {}, {}, {}, { saved++ }, {}, { _, _ -> }) } }
        compose.onNodeWithText("Keys on hallway shelf").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        assertEquals(1, saved)
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun panIsBoundedAndResetsAtUnitScale() {
        assertEquals(0f, Zoom.clampPan(50f, 1f, 400f))
        assertEquals(200f, Zoom.clampPan(999f, 2f, 400f))
        assertEquals(-600f, Zoom.clampPan(-999f, 4f, 400f))
        assertEquals(30f, Zoom.clampPan(30f, 2f, 400f))
        assertEquals(0f, Zoom.clampPan(Float.NaN, 2f, 400f))
    }

    @Test fun hiddenMemoryIsReadOnly() {
        val m = Fixtures.memory("m1", "Keys", 1, review = ReviewState.INVALIDATED)
        val state = MemoryDetailModel.build(m, null, Fixtures.ZONE)
        assertFalse(state.editable)
        compose.setContent { PcTheme { MemoryDetailScreen(state, {}, {}, {}, {}, {}, {}, { _, _ -> }) } }
        compose.onNodeWithText("Trust").assertIsNotEnabled()
        compose.onNodeWithText("Edit").assertIsNotEnabled()
    }

    @Test fun saveRejectedBecauseMemoryBecameHidden() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm("m1")
        withTimeout(5_000) { model.state.first { it.found } }
        model.startEdit()
        model.updateDraft("Keys in drawer")
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, review = ReviewState.INVALIDATED))
        withTimeout(5_000) { model.state.first { !it.editable } }
        model.saveEdit()
        assertEquals("This memory can't be edited", withTimeout(5_000) { model.state.first { it.message != null } }.message)
    }
}
