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
import com.rokid.context.orchestration.proactive.ProactiveFamily
import com.rokid.context.orchestration.proactive.ProactiveSettings
import com.rokid.context.orchestration.proactive.Surface
import com.rokid.context.orchestration.proactive.SuggestionActions
import com.rokid.context.orchestration.proactive.SuggestionRecord
import com.rokid.context.orchestration.proactive.SuggestionSurfaces
import com.rokid.context.orchestration.proactive.TriggerKind
import com.rokid.context.orchestration.proactive.generators.RenderedCard
import com.rokid.context.orchestration.proactive.generators.RenderedSource
import com.rokid.context.phone.ui.status.StatusUi
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

    private fun feed(render: suspend (SuggestionRecord, Long) -> RenderedCard? = { _, _ -> card }) =
        AndroidSuggestionFeed(app, { store }, settings, render, SuggestionActions({ store }, { surfaces }, { ts }), { ZoneId.of("Asia/Kolkata") }, { ts })

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
