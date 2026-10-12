package com.rokid.context.glasses.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MemoryLine
import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Verbosity
import com.rokid.context.glasses.hud.HudNav
import com.rokid.context.glasses.hud.HudView
import com.rokid.context.glasses.link.HudConnection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudRootTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_793_000_000_000L
    private fun frame(overlay: Overlay? = null) = StateFrame(
        seq = 1, capture = CaptureState.Running, intervalSec = 300, glassesBatteryPct = 82, phoneLinked = true,
        aiHealth = Health.Degraded("AI unavailable"), queueDepth = 3, latestNudge = NudgeLine("Milk is almost out", now),
        today = listOf(MemoryLine("m1", now - 60_000, "Keys on the hallway shelf"), MemoryLine("m2", now - 120_000, "Parked on level 2")),
        overlay = overlay,
    )
    private fun model(
        page: HudPage? = HudPage.GLANCE,
        overlay: Overlay? = null,
        verbosity: Verbosity = Verbosity.MEDIUM,
        connection: HudConnection = HudConnection.LIVE,
        paused: Boolean = false,
    ) = HudModel(
        view = HudView(page, overlay), frame = frame(overlay), settings = HudSettings(verbosity = verbosity), connection = connection,
        batteryPct = 82, paused = paused, nextCaptureAtMs = now + 252_000, nowMs = now, nav = HudNav(page = page ?: HudPage.GLANCE),
        confirmHighlightYes = true,
    )

    @Test fun glanceShowsCountdownNudgeAndBattery() {
        compose.setContent { HudRoot(model()) }
        compose.onNodeWithText("NEXT").assertExists()
        compose.onNodeWithText("4:12").assertExists()
        compose.onNodeWithText("Milk is almost out").assertExists()
        compose.onNodeWithText("82%").assertExists()
    }

    @Test fun offlineGlanceSaysOffline() {
        compose.setContent { HudRoot(model(connection = HudConnection.OFFLINE)) }
        compose.onNodeWithText("offline").assertExists()
    }

    @Test fun pausedGlanceShowsPausedNotACountdown() {
        compose.setContent { HudRoot(model(paused = true)) }
        compose.onAllNodesWithText("PAUSED").assertCountEquals(2)
        compose.onNodeWithText("4:12").assertDoesNotExist()
    }

    @Test fun todayListsMemories() {
        compose.setContent { HudRoot(model(page = HudPage.TODAY)) }
        compose.onNodeWithText("TODAY").assertExists()
        compose.onNodeWithText("Keys on the hallway shelf", substring = true).assertExists()
        compose.onNodeWithText("Parked on level 2", substring = true).assertExists()
    }

    @Test fun statusShowsShortHumanText() {
        compose.setContent { HudRoot(model(page = HudPage.STATUS, verbosity = Verbosity.MAX)) }
        compose.onNodeWithText("AI unavailable", substring = true).assertExists()
        compose.onNodeWithText("3 waiting", substring = true).assertExists()
        compose.onNodeWithText("every 5 min", substring = true).assertExists()
    }

    @Test fun silentShowsOnlyTheRecDot() {
        compose.setContent { HudRoot(model(page = null, verbosity = Verbosity.SILENT)) }
        compose.onNodeWithText("NEXT").assertDoesNotExist()
        compose.onNodeWithText("82%").assertDoesNotExist()
    }

    @Test fun outdatedPhoneShowsTheUpdatePrompt() {
        compose.setContent { HudRoot(model(connection = HudConnection.OUTDATED)) }
        compose.onNodeWithText("Update Proactive Context").assertExists()
        compose.onNodeWithText("NEXT").assertDoesNotExist()
    }

    @Test fun everyOverlayRenders() {
        var m by mutableStateOf(model())
        compose.setContent { HudRoot(m) }
        val cases = listOf(
            Overlay.Listening("what did I") to "what did I",
            Overlay.Answer("Where are my keys?", "On the hallway shelf", speaking = true) to "On the hallway shelf",
            Overlay.Nudge("Leave in 10 min") to "Leave in 10 min",
            Overlay.Confirm("p1", "Add milk?", "Shopping list") to "Add milk?",
            Overlay.PausedSplash to "Capture paused",
            Overlay.Toast("Phone not connected") to "Phone not connected",
        )
        for ((o, text) in cases) {
            m = model(overlay = o)
            compose.onNodeWithText(text, substring = true).assertExists()
        }
    }
}
