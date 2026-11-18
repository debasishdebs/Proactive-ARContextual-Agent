package com.rokid.context.phone.ui.settings.suggestions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.orchestration.proactive.InMemorySuggestionStore
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.orchestration.proactive.generators.RenderedSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Codex v3 r1 P1-5: the rendered rows follow the store; a Forget, a withdrawal or Delete all clears the text at once and an overlapping refresh never republishes it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SuggestionsControlTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = InMemorySuggestionStore()
    private val rec = SuggestionRecord("sg_1", 1_799_000_000_000L, "f2:wd:Q1", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.72, "wd:Q1", listOf("mem:m"), listOf("arrived", "visits=1"))
    private val card = RenderedCard("Golconda: last visit 12 Nov.", "from 1 item · 12 Nov", listOf(RenderedSource("memory", 1L)))

    @Before fun setUp() { ProactiveSettings.resetForTests(); store.add(rec) }
    @After fun tearDown() { scope.cancel() }

    private fun control(render: suspend (SuggestionRecord, Long) -> RenderedCard? = { _, _ -> card }) = AndroidSuggestionsControl(app, scope, { store }, render, {})
    private suspend fun await(c: AndroidSuggestionsControl, what: (SuggestionsUiState) -> Boolean) = withTimeout(10_000) { while (!what(c.state.value)) delay(10) }

    @Test fun rowsAreRenderedThenClearedTheMomentTheStoreIsCleared() = runBlocking {
        val c = control(); c.refresh()
        await(c) { it.rows.size == 1 }
        assertEquals(card.text, c.state.value.rows.single().text)
        store.clearAll() // Delete all
        await(c) { it.rows.isEmpty() }
        delay(300); assertTrue(c.state.value.rows.isEmpty())
    }

    @Test fun aWithdrawalByForgetClearsTheRowWithoutAnyScreenAction() = runBlocking {
        val c = control(); c.refresh(); await(c) { it.rows.size == 1 }
        assertEquals(1, store.withdrawWhere { "mem:m" in it.sourceIds }) // the Forget listener's sweep
        await(c) { it.rows.isEmpty() }
    }

    @Test fun anOverlappingRefreshDoesNotRepublishDeletedContent() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val c = control { _, _ -> started.complete(Unit); release.await(); card }
        c.refresh(); withTimeout(10_000) { started.await() } // a refresh is mid-render with the old record
        store.withdraw("sg_1") // Forget / Delete while it renders
        await(c) { it.rows.isEmpty() }
        release.complete(Unit) // the old refresh finishes
        delay(500)
        assertTrue("deleted text is not republished", c.state.value.rows.isEmpty())
    }

    @Test fun theDrivingNoticeAndStorageStateAreInTheState() {
        val c = control()
        assertEquals(SuggestionsModel.DRIVING_UNKNOWN, c.state.value.drivingLine); assertEquals(false, c.state.value.storageUnavailable)
        val prior = com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingAssessment
        try {
            com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingAssessment = { com.rokid.context.orchestration.proactive.DrivingAssessment(com.rokid.context.orchestration.proactive.DrivingState.NOT_DRIVING, "Not driving: walking for 3 min") }
            assertEquals("Not driving: walking for 3 min", SuggestionsModel.drivingLine())
            com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingAssessment = { throw IllegalStateException("x") }
            assertEquals("a failing read is the fail-closed unknown", SuggestionsModel.DRIVING_UNKNOWN, SuggestionsModel.drivingLine())
        } finally { com.rokid.context.orchestration.proactive.ProactiveEnvironment.drivingAssessment = prior }
    }

    private val surfaces = object : com.rokid.context.orchestration.proactive.SuggestionSurfaces {
        val off = ArrayList<com.rokid.context.orchestration.proactive.Surface>(); var all = 0; val withdrawn = ArrayList<String>()
        override fun withdrawSurface(surface: com.rokid.context.orchestration.proactive.Surface) { off += surface }
        override fun withdrawAll() { all++ }
        override fun withdraw(ids: Collection<String>) { withdrawn += ids }
    }
    private fun control2() = AndroidSuggestionsControl(app, scope, { store }, { _, _ -> card }, {}, com.rokid.context.orchestration.proactive.SuggestionActions({ store }, { surfaces }, { 1_799_000_000_000L }), { surfaces })

    @Test fun theDeliverySwitchesPersistIndependentlyAndTurningOneOffWithdrawsThatSurface() = runBlocking {
        val c = control2()
        assertEquals(false, c.state.value.deliverPhone || c.state.value.deliverHud || c.state.value.deliverNexus)
        c.setDeliver(com.rokid.context.orchestration.proactive.Surface.HUD, true)
        await(c) { it.deliverHud }; assertTrue(!c.state.value.deliverPhone && !c.state.value.deliverNexus)
        assertTrue(ProactiveSettings.of(app).deliverHud.value)
        c.setDeliver(com.rokid.context.orchestration.proactive.Surface.HUD, false)
        await(c) { !it.deliverHud }; assertEquals(listOf(com.rokid.context.orchestration.proactive.Surface.HUD), surfaces.off)
    }

    @Test fun theMasterOffWithdrawsEverySurface() = runBlocking {
        val c = control2(); c.setMaster(true); c.setMaster(false); await(c) { !it.master }; assertEquals(1, surfaces.all)
    }

    @Test fun dismissSnoozeThumbsAndNeverWriteCountersAndWithdraw() = runBlocking {
        val c = control2(); c.refresh(); await(c) { it.rows.size == 1 }
        c.thumb("sg_1", false); await(c) { it.learnedCount == 1 }; assertEquals(1, store.counters()["fb_down"]); assertEquals("a thumb keeps the row", 1, c.state.value.rows.size)
        c.dismiss("sg_1"); await(c) { it.rows.isEmpty() }; assertEquals(listOf("sg_1"), surfaces.withdrawn); assertEquals(1, store.counters()["fb_dismiss"])
        store.add(rec); c.refresh(); await(c) { it.rows.size == 1 }
        c.never("sg_1"); await(c) { it.rows.isEmpty() }; assertEquals(1, c.state.value.neverCount)
    }

    @Test fun resetLearningClearsTheCountersButNotTheNeverList() = runBlocking {
        val c = control2(); store.addNever("f2:x"); c.thumb("sg_1", true); await(c) { it.learnedCount == 1 }
        c.resetLearning(); await(c) { it.learnedCount == 0 }; assertEquals(setOf("f2:x"), store.never()); assertTrue(store.feedback().keys.isEmpty())
    }
}
