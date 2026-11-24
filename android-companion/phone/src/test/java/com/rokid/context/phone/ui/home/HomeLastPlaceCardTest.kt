package com.rokid.context.phone.ui.home

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceCardLogEntity
import com.rokid.context.phone.ui.status.StatusUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/** B2: Home's "Last place card" row: only while the newest card is under 6 h old, tapping it opens the Place Guide list. Synthetic names only. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeLastPlaceCardTest {
    @get:Rule val compose = createComposeRule()
    private val graphCtx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    @org.junit.Before fun freshGraph() { com.rokid.context.phone.ui.TestGraph.fresh(graphCtx) }
    @org.junit.After fun closeGraph() { com.rokid.context.phone.ui.TestGraph.close(graphCtx) }
    private val now = 1_793_000_000_000L
    private fun row(at: Long, kind: String = "PIN", delivery: String = CardDelivery.SENT) = PlaceCardLogEntity("h1", at, "user:x", "Site A", kind, null, null, null, "c1", delivery)

    @Test fun aRowUnderSixHoursShows() {
        val c = LastPlaceCards.of(row(now - 3_600_000), now, ZoneId.of("UTC"))
        assertNotNull(c); assertEquals("Site A", c!!.title); assertEquals("your pin · sent to glasses", c.body); assertEquals("h1", c.id)
        assertEquals(true, c.meta.startsWith("Last place card · "))
    }

    @Test fun aRowOfSixHoursOrOlderIsNotShown() {
        assertNull(LastPlaceCards.of(row(now - LastPlaceCards.MAX_AGE_MS), now))
        assertNull(LastPlaceCards.of(row(now - LastPlaceCards.MAX_AGE_MS - 1), now))
        assertNotNull(LastPlaceCards.of(row(now - LastPlaceCards.MAX_AGE_MS + 1), now))
    }

    @Test fun theRowAppearsOnHomeAndOpensTheList() {
        var opened = 0
        val state = HomeUiState(status = StatusUi("Capturing", live = true, paused = false, batteryPct = 80, banner = null), lastPlaceCard = LastPlaceCards.of(row(now - 60_000, "PHOTO", CardDelivery.NOT_SENT_LINK), now, ZoneId.of("UTC")))
        compose.setContent { PcTheme { HomeScreen(state, onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {}, onOpenPlaceCards = { opened++ }) } }
        compose.onNodeWithTag("home_list").performScrollToNode(hasText("Site A"))
        compose.onNodeWithTag("last_place_card").assertIsDisplayed()
        compose.onNodeWithTag("last_place_card").performClick()
        assertEquals(1, opened)
    }

    @Test fun theSourceFollowsHistoryChangesNotATimer() = kotlinx.coroutines.runBlocking {
        val db = com.rokid.context.memory.place.PlacesDatabase.inMemory(graphCtx)
        val pack = com.rokid.context.memory.place.PlacePack(db, null)
        var at = System.currentTimeMillis()
        val history = com.rokid.context.work.frames.place.PlaceCardHistory(pack.cardLogDao, clock = { at }, newId = { "n$at" })
        com.rokid.context.work.frames.place.PlaceGraph.installForTests(pack); com.rokid.context.work.frames.place.PlaceGraph.installHistoryForTests(history)
        try {
            val source = com.rokid.context.phone.place.AndroidPlaceCardHomeSource(graphCtx)
            val seen = kotlinx.coroutines.flow.MutableStateFlow<LastPlaceCard?>(null)
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { source.last.collect { seen.value = it } }
            history.add(com.rokid.context.work.frames.place.PlaceHistoryEvent(com.rokid.context.work.frames.place.HistoryKind.PIN, "user:x", "Site A", cardId = "c1"))
            withTimeout(10_000) { seen.first { it?.title == "Site A" && it.body.contains("waiting") } }
            history.markDelivery("c1", CardDelivery.SENT)
            withTimeout(10_000) { seen.first { it?.body?.contains("sent to glasses") == true } }
            history.clearAll()
            withTimeout(10_000) { seen.first { it == null } }
            job.cancel()
        } finally { com.rokid.context.work.frames.place.PlaceGraph.installForTests(null); db.close() }
    }

    @Test fun noRowWhenThereIsNoRecentCard() {
        compose.setContent { PcTheme { HomeScreen(HomeUiState(status = StatusUi("Capturing", live = true, paused = false, batteryPct = 80, banner = null)), onQuestionChange = {}, onAsk = {}, onOpenAsk = {}, onTogglePause = {}, onBannerAction = {}) } }
        compose.onNodeWithTag("home_list").assertIsDisplayed()
        compose.onAllNodesWithTagCount("last_place_card", 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String, n: Int) =
        assertEquals(n, onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size)
}
