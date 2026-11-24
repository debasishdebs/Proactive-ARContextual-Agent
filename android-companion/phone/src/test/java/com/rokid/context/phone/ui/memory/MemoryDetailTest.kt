package com.rokid.context.phone.ui.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryDetailTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<MemoryDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(id: String) = MemoryDetailViewModel(id, graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }).also { vms += it }

    /** Codex v3 r1 P3-13: a SHORT fact is labelled with its v3 meaning (Quick recall, nothing is deleted when it leaves it), never "Short-term". */
    @Test fun aShortFactIsLabelledQuickRecall() {
        val m = Fixtures.memory("m2", "Keys on shelf", Fixtures.at(TODAY, 8, 5), frameId = "f1", tags = "keys").copy(tier = MemoryTier.SHORT)
        assertEquals("Quick recall", MemoryDetailModel.build(m, Fixtures.frame("f1", Fixtures.at(TODAY, 8), path = "/f1.jpg"), Fixtures.ZONE).tierLabel)
        assertEquals("1 older detail is no longer available; the summary is kept", com.rokid.context.phone.ui.home.expiredSourcesText(1))
    }

    @Test fun modelLabels() {
        val m = Fixtures.memory("m1", "Keys on shelf", Fixtures.at(TODAY, 8, 5), frameId = "f1", tags = "keys, hallway", review = ReviewState.NEEDS_REVIEW)
            .copy(tier = MemoryTier.LONG)
        val s = MemoryDetailModel.build(m, Fixtures.frame("f1", Fixtures.at(TODAY, 8), path = "/f1.jpg"), Fixtures.ZONE)
        assertEquals(listOf("keys", "hallway"), s.tags)
        assertEquals("Long-term", s.tierLabel)
        assertEquals("Needs review", s.reviewLabel)
        assertEquals("Mon 12 Oct · 08:05", s.createdLabel)
        assertEquals("/f1.jpg", s.framePath)
        assertEquals(1f, Zoom.clamp(0.2f)); assertEquals(5f, Zoom.clamp(9f)); assertEquals(1f, Zoom.clamp(Float.NaN)); assertEquals(2.5f, Zoom.clamp(2.5f))
    }

    /** Ruling 11: retention deletes the photo but keeps the memory; the screen says so instead of showing a hole. */
    @Test fun expiredPhotoShowsPlaceholder() {
        val m = Fixtures.memory("m1", "Keys on shelf", Fixtures.at(TODAY, 8, 5), frameId = "f1")
        val expired = MemoryDetailModel.build(m, null, Fixtures.ZONE)
        assertEquals(true, expired.photoExpired)
        assertEquals(null, expired.framePath)
        assertEquals("Photo expired", MemoryDetailModel.PHOTO_EXPIRED)
        val present = MemoryDetailModel.build(m, Fixtures.frame("f1", Fixtures.at(TODAY, 8), path = "/f1.jpg"), Fixtures.ZONE)
        assertEquals(false, present.photoExpired)
        assertEquals(false, MemoryDetailModel.build(Fixtures.memory("m2", "No photo", 1), null, Fixtures.ZONE).photoExpired) // never had one
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
    }

    @Test fun panIsBoundedAndResetsAtUnitScale() {
        assertEquals(0f, Zoom.clampPan(50f, 1f, 400f))
        assertEquals(200f, Zoom.clampPan(999f, 2f, 400f))
        assertEquals(-600f, Zoom.clampPan(-999f, 4f, 400f))
        assertEquals(30f, Zoom.clampPan(30f, 2f, 400f))
        assertEquals(0f, Zoom.clampPan(Float.NaN, 2f, 400f))
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
