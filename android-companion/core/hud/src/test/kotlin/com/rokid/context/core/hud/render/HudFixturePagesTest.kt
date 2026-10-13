package com.rokid.context.core.hud.render

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * R-73 (spec section 6.4): every HUD page at every verbosity, and every [Overlay] subtype, renders from a fixture
 * [StateFrame] through [HudRoot] without throwing and without raw error text (spec section 6.3).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HudFixturePagesTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_793_000_000_000L

    /** One fixture per [Overlay] subtype; the exhaustive `when` stops compiling when Protocol gains a subtype. */
    private val overlays: List<Overlay> = listOf(
        Overlay.Listening("where are my"),
        Overlay.Answer("Where are my keys?", "On the hallway shelf", speaking = true),
        Overlay.Nudge("Leave in 10 min"),
        Overlay.Confirm("prop_1", "Add milk?", "Shopping list"),
        Overlay.PausedSplash,
        Overlay.Toast("Added to shopping list", undoProposalId = "prop_1"),
    ).onEach { o ->
        when (o) {
            is Overlay.Listening, is Overlay.Answer, is Overlay.Nudge, is Overlay.Confirm, Overlay.PausedSplash, is Overlay.Toast -> Unit
        }
    }

    private fun frame(overlay: Overlay?) = StateFrame(
        seq = 7, capture = CaptureState.Running, nextCaptureAtMs = now + 252_000, intervalSec = 300, glassesBatteryPct = 64, phoneLinked = true,
        aiHealth = Health.Degraded("AI unavailable"), queueDepth = 2, lastSentAtMs = now - 30_000,
        latestNudge = NudgeLine("Milk is almost out", now - 90_000),
        today = listOf(MemoryLine("m1", now - 60_000, "Keys on the hallway shelf"), MemoryLine("m2", now - 120_000, "Parked on level 2")),
        overlay = overlay,
    )

    private fun model(page: HudPage, verbosity: Verbosity, overlay: Overlay?): HudModel {
        val f = frame(overlay)
        return HudModel(
            view = HudView(page, f.overlay), frame = f, settings = HudSettings(verbosity = verbosity), connection = HudConnection.LIVE,
            batteryPct = f.glassesBatteryPct, paused = false, nextCaptureAtMs = f.nextCaptureAtMs, nowMs = now, nav = HudNav(page = page),
            confirmHighlightYes = true,
        )
    }

    private fun texts(n: SemanticsNode): List<String> = buildList {
        n.config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
        n.config.getOrNull(SemanticsProperties.EditableText)?.let { add(it.text) }
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { addAll(it) }
        n.children.forEach { addAll(texts(it)) }
    }

    @Test fun everyHudPageAndOverlayRendersFromFixtures() {
        val cases = HudPage.entries.flatMap { p -> Verbosity.entries.map { v -> "$p/$v" to model(p, v, null) } } +
            overlays.map { o -> "overlay ${o::class.simpleName}" to model(HudPage.GLANCE, Verbosity.MEDIUM, o) }
        assertEquals(3 * 3 + 6, cases.size)
        var m by mutableStateOf(cases.first().second)
        compose.setContent { HudRoot(m) }
        val failures = mutableListOf<String>()
        for ((name, model) in cases) {
            try {
                m = model
                compose.waitForIdle()
                val shown = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap(::texts)
                shown.filter { RAW_ERROR.containsMatchIn(it) }.forEach { failures += "$name: raw error text \"$it\"" }
            } catch (t: Throwable) {
                failures += "$name: threw ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        assertTrue(failures.joinToString("\n", prefix = "HUD cases failed:\n"), failures.isEmpty())
    }

    private companion object {
        /** Spec section 6.3: no stack-trace or class-name text reaches the user. */
        val RAW_ERROR = Regex("Exception|java\\.|kotlin\\.|at com\\.rokid")
    }
}
