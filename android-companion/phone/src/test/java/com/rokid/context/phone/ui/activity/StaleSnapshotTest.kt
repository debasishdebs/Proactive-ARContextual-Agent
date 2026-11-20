package com.rokid.context.phone.ui.activity

import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.memory.MemoryDetailViewModel
import com.rokid.context.phone.ui.preview.PreviewHost
import com.rokid.context.phone.ui.preview.configHidden
import com.rokid.context.phone.ui.preview.framePathFlow
import com.rokid.context.phone.ui.memory.MemoryDetailUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic: fake flows that emit the inconsistent intermediate state of a Forget transaction (combined-flows audit P2/P3). */
class StaleSnapshotTest {
    private val at = Fixtures.at(TODAY, 9)

    @Test fun aForgottenMemoryNeverReachesTheRowsFromTheIntermediateEmission() = runBlocking {
        val kept = Fixtures.memory("m1", "Keys on shelf", at)
        val forgotten = Fixtures.memory("m2", "Secret caption", at, frameId = "f2")
        // the frames flow already re-emitted without f2; the memories list is still the old one
        val stale = listOf(kept, forgotten)
        val rows = Timeline.stillVisible(stale) { ids -> listOf(kept).filter { it.id in ids } } // the live read: m2 is gone
        assertEquals(listOf("m1"), rows.map { it.id })
        val sections = Timeline.sections(rows, emptyList(), Fixtures.ZONE, TODAY)
        assertFalse(sections.flatMap { it.rows }.any { it.text == "Secret caption" })
    }

    @Test fun aMemoryThatIsNoLongerOkIsDroppedAndALookupFailureKeepsTheList() = runBlocking {
        val a = Fixtures.memory("a", "A", at); val b = Fixtures.memory("b", "B", at)
        assertEquals(listOf("a"), Timeline.stillVisible(listOf(a, b)) { listOf(a, b.copy(reviewState = ReviewState.INVALIDATED)) }.map { it.id })
        assertEquals(listOf("a", "b"), Timeline.stillVisible(listOf(a, b)) { error("db closed") }.map { it.id })
        assertTrue(Timeline.stillVisible(emptyList()) { error("never read for nothing") }.isEmpty())
    }

    @Test fun anObservedPhotoPathEndsAtNullWhenTheRowIsGoneAndAnUnobservedHostReadsOnce() = runBlocking {
        val path = MutableStateFlow<String?>("/f1.jpg")
        val host = PreviewHost(MemoryDetailUiState(), framePath = { "/once.jpg" }, observeFramePath = { path })
        assertEquals("/f1.jpg", host.framePathFlow("f1").first())
        path.value = null
        assertNull(host.framePathFlow("f1").first())
        assertEquals(listOf<String?>("/once.jpg"), PreviewHost(MemoryDetailUiState(), framePath = { "/once.jpg" }).framePathFlow("f1").toList())
    }

    @Test fun theConfigSectionOfAForgottenLiveMemoryIsHiddenButNotWhileLoadingOrForOtherPreviews() {
        assertTrue(configHidden(live = true, loading = false, found = false))
        assertFalse(configHidden(live = true, loading = true, found = false))
        assertFalse(configHidden(live = true, loading = false, found = true))
        assertFalse(configHidden(live = false, loading = false, found = false))
    }

    @Test fun aFrameThatIsGoneDropsTheDetailsStalePath() = runBlocking {
        val m = Fixtures.memory("m1", "Keys", at, frameId = "f1")
        var present = true
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val seen = mutableListOf<Pair<String?, Boolean>>()
        val flow = MemoryDetailViewModel.detailFlow(flowOf(m), changes, { if (present) Fixtures.frame("f1", at, "/f1.jpg") else null }, { Fixtures.ZONE })
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { flow.collect { seen += it.framePath to it.photoExpired } }
        present = false
        changes.emit(Unit) // retention expired the frame; the memory row did not change
        job.cancel()
        assertEquals(listOf<Pair<String?, Boolean>>("/f1.jpg" to false, null to true), seen)
    }
}
