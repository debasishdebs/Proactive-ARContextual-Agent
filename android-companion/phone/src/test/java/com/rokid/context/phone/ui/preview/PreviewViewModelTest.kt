package com.rokid.context.phone.ui.preview

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The previewer reads its item again when the item changes (a redaction, a Forget), so removed content never stays on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PreviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val t = Fixtures.at(TODAY, 9)
    private fun vm(data: FakeActivityData, ref: PreviewRef) = PreviewViewModel(ref, data, { Fixtures.ZONE }, { TODAY })
    private fun kinds(vm: PreviewViewModel) = vm.state.value.item?.sections()?.map { it::class.simpleName }

    @Test fun aRedactedAnswerLosesItsContentAtOnceAndAMissingOneBecomesUnavailable() = runBlocking<Unit> {
        val data = FakeActivityData(asks = listOf(Fixtures.ask("a1", t, sources = """["m1"]""", rating = 1)), mems = listOf(Fixtures.memory("m1", "Keys", t)))
        val vm = vm(data, PreviewRef(RefKind.ASK, "a1"))
        withTimeout(5_000) { vm.state.first { !it.loading && it.item != null } }
        assertTrue("RatingSection" in kinds(vm).orEmpty() && "SourcesSection" in kinds(vm).orEmpty())
        data.asks = listOf(Fixtures.ask("a1", t, sources = """["m1"]""", redacted = true, answer = "Removed — a source was forgotten"))
        data.changed.emit(Unit)
        withTimeout(5_000) { vm.state.first { it.item?.sections()?.none { s -> s is PreviewSection.SourcesSection } == true } }
        assertEquals(listOf("TextSection", "TextSection", "ProposalSection", "AudioSection"), kinds(vm))
        data.asks = emptyList()
        data.changed.emit(Unit)
        withTimeout(5_000) { vm.state.first { it.item == null } }
        assertNull(vm.state.value.item)
    }

    @Test fun anOldAnswerBeyondTheRecentOnesStillOpensById() = runBlocking<Unit> {
        val data = FakeActivityData(asks = listOf(Fixtures.ask("old", 1)))
        assertEquals("old", withTimeout(5_000) { vm(data, PreviewRef(RefKind.ASK, "old")).state.first { !it.loading } }.item?.id)
    }

    @Test fun afterAPhotoIsForgottenThePreviewStaysOnTheOtherPhotos() = runBlocking<Unit> {
        val data = FakeActivityData(frames = listOf(Fixtures.frame("f1", t), Fixtures.frame("f2", t + 1000)))
        val vm = vm(data, PreviewRef(RefKind.FRAME, "f1"))
        withTimeout(5_000) { vm.state.first { it.item != null } }
        vm.showPhoto(photoAfterForget(listOf("f1", "f2"), "f1")!!)
        assertEquals("f2", withTimeout(5_000) { vm.state.first { it.item?.id == "f2" } }.item?.id)
    }
}
