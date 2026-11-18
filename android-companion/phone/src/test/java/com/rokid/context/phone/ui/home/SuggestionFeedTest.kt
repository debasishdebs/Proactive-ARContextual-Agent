package com.rokid.context.phone.ui.home

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.orchestration.proactive.InMemorySuggestionStore
import com.rokid.context.orchestration.proactive.ProactiveDelivery
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.SuggestionPublications
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.Surface
import com.rokid.context.orchestration.proactive.SuggestionActions
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.SuggestionSurfaces
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.orchestration.proactive.generators.RenderedSource
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/** v3.1: the phone feed is derived from the store: a delivered, not withdrawn record is a card; everything else (switch off, withdrawn, forgotten, dismissed anywhere) is no card. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SuggestionFeedTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val ts = 1_799_000_000_000L
    private val store = InMemorySuggestionStore()
    private val card = RenderedCard("Golconda: last visit 12 Nov.", "from 2 items · 12 Nov", listOf(RenderedSource("memory", ts - 86_400_000L)), "Golconda", "last visit 12 Nov.")
    private fun rec(id: String, delivered: Set<String> = setOf("phone")) = SuggestionRecord(id, ts, "f2:$id", ProactiveFamily.PLACE_RECALL, TriggerKind.TICK, 0.7, "wd:Q1", listOf("mem:m"), listOf("arrived", "visits=1"), delivered)
    private val surfaces = object : SuggestionSurfaces { val withdrawn = ArrayList<String>(); override fun withdraw(ids: Collection<String>) { withdrawn += ids } }
    private lateinit var settings: ProactiveSettings

    @Before fun setUp() {
        ProactiveSettings.resetForTests()
        settings = ProactiveSettings(app.getSharedPreferences("test_feed_settings", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
    }

    /** The gates live in `ProactiveDelivery.phoneFeed` (DeliveryTest); here the seam answers like it: the delivered, not withdrawn records of the store with the card, or nothing while a gate is closed. */
    private var gateOpen = true
    private fun feed(render: suspend (SuggestionRecord, Long) -> RenderedCard? = { _, _ -> card }, ticks: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.flowOf(Unit), events: kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(), cards: (suspend (Long) -> List<ProactiveDelivery.FeedCard>)? = null) =
        AndroidSuggestionFeed(app, { store }, settings, cards ?: { now -> if (!gateOpen) emptyList() else store.records().filter { Surface.PHONE.token in it.delivered }.mapNotNull { r -> render(r, now)?.let { ProactiveDelivery.FeedCard(r, it) } } },
            SuggestionActions({ store }, { surfaces }, { ts }, SuggestionPublications()), { ZoneId.of("Asia/Kolkata") }, { ts }, ticks, events)

    private fun items(f: AndroidSuggestionFeed, until: (List<SuggestionCardUi>) -> Boolean): List<SuggestionCardUi> = runBlocking { withTimeout(10_000) { f.items.first(until) } }
    private fun on() { settings.setMaster(true); settings.setDeliver(Surface.PHONE, true) }

    @Test fun nothingShowsOnAFreshInstallEvenWithADeliveredRecord() { store.add(rec("a")); assertTrue(items(feed()) { true }.isEmpty()) }
    @Test fun theMasterAloneIsNotEnoughAndThePhoneSwitchAloneIsNotEither() {
        store.add(rec("a")); settings.setMaster(true); assertTrue(items(feed()) { true }.isEmpty())
        settings.setMaster(false); settings.setDeliver(Surface.PHONE, true); assertTrue(items(feed()) { true }.isEmpty())
    }
    @Test fun aDeliveredRecordIsACardWithTemplateTextAndAWhy() {
        on(); store.add(rec("a")); store.add(rec("hudonly", setOf("hud")))
        val c = items(feed()) { it.isNotEmpty() }.single()
        assertEquals("a", c.id); assertEquals("Golconda", c.title); assertEquals("last visit 12 Nov.", c.line); assertEquals("Place recall", c.why.family); assertTrue(c.why.sources.isNotEmpty())
    }
    @Test fun aRecordWhoseSourcesCannotBeRenderedIsNotShown() { on(); store.add(rec("a")); assertTrue(items(feed({ _, _ -> null })) { true }.isEmpty()) }
    @Test fun aWithdrawalFromAnySurfaceRemovesTheCardAtOnce() {
        on(); store.add(rec("a")); val f = feed(); items(f) { it.size == 1 }
        f.dismiss("a")
        assertTrue(items(f) { it.isEmpty() }.isEmpty()); assertEquals(listOf("a"), surfaces.withdrawn); assertEquals(1, store.feedback().keys["f2:a"]!!.dismissCount)
    }
    @Test fun aForgetOrDeleteAllDropsTheCardAndItsText() {
        on(); store.add(rec("a")); store.add(rec("b")); val f = feed(); items(f) { it.size == 2 }
        store.withdrawWhere { it.id == "a" }; items(f) { it.size == 1 }
        store.clearAll(); assertTrue(items(f) { it.isEmpty() }.isEmpty())
    }
    @Test fun neverSnoozeAndThumbsGoThroughTheSameActions() {
        on(); store.add(rec("a")); store.add(rec("b")); store.add(rec("c")); val f = feed()
        f.thumb("c", true); assertEquals(1, store.feedback().keys["f2:c"]!!.vote); assertEquals("a thumb keeps the card", 3, items(f) { it.size == 3 }.size)
        f.never("a"); assertEquals(setOf("f2:a"), store.never())
        f.snooze("b"); assertTrue(store.feedback().keys["f2:b"]!!.snoozeUntilMs > ts)
        assertEquals(listOf("c"), items(f) { it.size == 1 }.map { it.id })
    }
    @Test fun aClosedGateRendersNothingAndClearsTheRenderedState() {
        on(); store.add(rec("a"))
        val tick = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(replay = 1); tick.tryEmit(Unit)
        val f = feed(ticks = tick); items(f) { it.size == 1 }
        gateOpen = false; tick.tryEmit(Unit) // a pause or a zone hold: no store change, only the next render
        assertTrue("no previously delivered text stays", items(f) { it.isEmpty() }.isEmpty())
        gateOpen = true; tick.tryEmit(Unit); items(f) { it.size == 1 }
    }
    // ---- Codex v3.1 r2 P1-2: gate-CLOSE events clear the rendered card at once, not on the 10 s tick -------------------------------------------------------------

    /** Collects what Home would render, on a thread of its own; [events] are the fake zone / pause / driving close flows. */
    private fun closeClearsAtOnce(trigger: (kotlinx.coroutines.flow.MutableSharedFlow<Unit>) -> Unit) {
        on(); store.add(rec("a"))
        val events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val never = kotlinx.coroutines.flow.flow<Unit> { emit(Unit); kotlinx.coroutines.awaitCancellation() } // the backstop tick never fires again: only the event can clear
        val slow = java.util.concurrent.CountDownLatch(1) // the re-derive itself is slow (a lease, provenance): the card must already be gone while it runs
        var closed = false
        val f = feed(ticks = never, events = events, cards = { now -> if (closed) { slow.await(5, java.util.concurrent.TimeUnit.SECONDS); emptyList() } else store.records().filter { Surface.PHONE.token in it.delivered }.mapNotNull { r -> ProactiveDelivery.FeedCard(r, card) } })
        val seen = java.util.concurrent.LinkedBlockingQueue<List<SuggestionCardUi>>()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { f.items.collect { seen.put(it) } }
        try {
            assertEquals(1, seen.poll(10, java.util.concurrent.TimeUnit.SECONDS)!!.size)
            Thread.sleep(300); seen.clear() // the initial derivations (store, backstop) have settled
            closed = true; trigger(events)
            assertTrue("cleared before the re-derive finished (it is still blocked)", seen.poll(10, java.util.concurrent.TimeUnit.SECONDS)!!.isEmpty())
        } finally { slow.countDown(); job.cancel() }
    }
    @Test fun enteringAZoneClearsTheCardInTheSamePass() = closeClearsAtOnce { assertTrue(it.tryEmit(Unit)) }
    @Test fun aPauseClearsTheCardInTheSamePass() = closeClearsAtOnce { assertTrue(it.tryEmit(Unit)) }
    @Test fun drivingStartingClearsTheCardInTheSamePass() = closeClearsAtOnce { assertTrue(it.tryEmit(Unit)) }

    @Test fun theProductionEventSourcesAreWired() {
        val seen = java.util.concurrent.LinkedBlockingQueue<Unit>()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { AndroidSuggestionFeed.defaultGateEvents({ null }).collect { seen.put(it) } }
        try {
            Thread.sleep(300)
            com.rokid.context.orchestration.proactive.SuggestionGateEvents.closed(); assertTrue("zone/pause close", seen.poll(5, java.util.concurrent.TimeUnit.SECONDS) != null)
            var clock = 0L; val t = com.rokid.context.orchestration.proactive.DrivingTracker { clock }; var fired = 0; t.onBlocking = { fired++ }
            while (clock <= 11 * 60_000L) { t.heartbeat(false); clock += 30_000L }
            t.calm(com.rokid.context.orchestration.proactive.CalmSource.PHONE_ACTIVITY, com.rokid.context.orchestration.proactive.Calm.WALKING); t.calm(com.rokid.context.orchestration.proactive.CalmSource.PHONE_STEPS, com.rokid.context.orchestration.proactive.Calm.STILL)
            assertEquals(com.rokid.context.orchestration.proactive.DrivingState.NOT_DRIVING, t.assess().state); assertEquals("no transition to blocking yet", 0, fired)
            t.vehicle(); assertEquals("NOT_DRIVING -> DRIVING fires the close event", 1, fired); t.vehicle(); assertEquals("only the transition", 1, fired)
        } finally { job.cancel() }
    }

    @Test fun theQuietHoursBoundaryIsTheNextWindowStart() {
        val z = ZoneId.of("Asia/Kolkata"); val q = com.rokid.context.core.model.QuietHours(22 * 60, 6 * 60)
        val noon = java.time.LocalDateTime.of(2026, 11, 18, 12, 0).atZone(z).toInstant().toEpochMilli()
        assertEquals(java.time.LocalDateTime.of(2026, 11, 18, 22, 0).atZone(z).toInstant().toEpochMilli(), AndroidSuggestionFeed.nextQuietStart(q, noon, z))
        val late = java.time.LocalDateTime.of(2026, 11, 18, 23, 0).atZone(z).toInstant().toEpochMilli()
        assertEquals(java.time.LocalDateTime.of(2026, 11, 19, 22, 0).atZone(z).toInstant().toEpochMilli(), AndroidSuggestionFeed.nextQuietStart(q, late, z))
        assertEquals(null, AndroidSuggestionFeed.nextQuietStart(com.rokid.context.core.model.QuietHours(60, 60), noon, z))
    }

    @Test fun theBuildFeedMergesSuggestionsByTimeWithItsOwnKind() {
        val c = SuggestionCardUi("s", ts, "Golconda", "last visit 12 Nov.", "from 2 items", com.rokid.context.phone.ui.settings.suggestions.WhyUi("Place recall", "t", emptyList(), emptyList()))
        val out = Feed.build(emptyList(), emptyList(), java.time.LocalDate.of(2026, 11, 18), ZoneId.of("Asia/Kolkata"), suggestions = listOf(c))
        assertTrue(out.single() is FeedItem.Suggestion)
    }

    @Test fun theHomeCardShowsTextSourcesAndTheWhySheet() {
        val item = FeedItem.Suggestion("sg_1", ts, "12:00", "Golconda", "last visit 12 Nov.", "from 2 items · 12 Nov", com.rokid.context.phone.ui.settings.suggestions.WhyUi("Place recall", "You arrived", listOf("Place memory · 12 Nov"), listOf("Score 0.72 (needs 0.35).")))
        val acted = ArrayList<String>()
        compose.setContent { PcTheme { HomeScreen(HomeUiState(status = StatusUi("Capturing", live = true, paused = false, batteryPct = 80, banner = null), feed = listOf(item)), {}, {}, {}, {}, {},
            onSuggestion = SuggestionActionsUi({ acted += "dismiss:$it" }, { acted += "snooze:$it" }, { acted += "never:$it" }, { id, up -> acted += "thumb:$id:$up" })) } }
        compose.onNodeWithTag("home_list").performScrollToNode(hasTestTag("suggestion_why_sg_1"))
        compose.onNodeWithTag("suggestion_why_sg_1").assertIsDisplayed()
        compose.onNodeWithTag("suggestion_dismiss_sg_1").performClick(); compose.onNodeWithTag("suggestion_snooze_sg_1").performClick()
        compose.onNodeWithTag("suggestion_up_sg_1").performClick(); compose.onNodeWithTag("suggestion_down_sg_1").performClick()
        compose.onNodeWithTag("suggestion_why_sg_1").performClick()
        compose.onNodeWithTag("why_family").assertIsDisplayed()
        compose.onNodeWithTag("why_never").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(listOf("dismiss:sg_1", "snooze:sg_1", "thumb:sg_1:true", "thumb:sg_1:false", "never:sg_1"), acted)
        compose.onAllNodes(hasText("Place recall")).assertCountEquals(0) // the sheet closed
    }
}
