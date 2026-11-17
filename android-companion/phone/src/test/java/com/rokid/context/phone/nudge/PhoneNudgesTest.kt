package com.rokid.context.phone.nudge

import com.rokid.context.core.model.NudgeLine
import com.rokid.context.core.model.Overlay
import com.rokid.context.memory.db.NudgeEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneNudgesTest {
    private val start = 50_000_000L
    private fun row(id: String, ts: Long, shownOn: String, text: String = "Milk is nearly out", redacted: Boolean = false) =
        NudgeEntity(id, ts, text, "why", "[]", 1, "h", shownOn, redacted)

    @Test fun toastOnlyForNewMaxNudges() = runTest {
        val rows = MutableStateFlow(listOf(row("old", start - 60_000, "toast")))
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        runCurrent()
        assertNull("never replayed after a restart", port.overlay.value)
        assertEquals(NudgeLine("Milk is nearly out", start - 60_000), port.latest.value)

        rows.value = listOf(row("new", start + 1_000, "toast", text = "x".repeat(100)))
        runCurrent()
        assertEquals(Overlay.Nudge("x".repeat(80)), port.overlay.value)
        advanceTimeBy(7_900); runCurrent()
        assertEquals(Overlay.Nudge("x".repeat(80)), port.overlay.value)
        advanceTimeBy(200); runCurrent()
        assertNull(port.overlay.value)
        assertEquals(80, port.latest.value?.text?.length)
    }

    @Test fun glanceNudgeHasNoToast() = runTest {
        val rows = MutableStateFlow(emptyList<NudgeEntity>())
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        rows.value = listOf(row("g", start + 10, "glance"))
        runCurrent()
        assertNull(port.overlay.value)
        assertEquals("Milk is nearly out", port.latest.value?.text)
    }

    @Test fun silentNudgeNotOnGlance() = runTest {
        val port = NudgePort(MutableStateFlow(listOf(row("s", start + 10, "phone"))), backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        runCurrent()
        assertNull(port.latest.value)
        assertNull(port.overlay.value)
    }

    @Test fun redactionClearsLatest() = runTest {
        val rows = MutableStateFlow(listOf(row("n", start + 10, "toast")))
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        runCurrent()
        assertEquals("Milk is nearly out", port.latest.value?.text)
        rows.value = listOf(row("n", start + 10, "toast", redacted = true))
        runCurrent()
        assertNull(port.latest.value)
        assertNull(port.overlay.value)
    }

    @Test fun deletingTheNewestNudgeClearsItsToastAndLeavesTheOlderSurvivor() = runTest {
        val older = row("older", start - 5_000, "glance", text = "Older survivor")
        val rows = MutableStateFlow(listOf(older))
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        runCurrent()
        rows.value = listOf(row("new", start + 1_000, "toast", text = "forgotten text"), older)
        runCurrent()
        assertEquals(Overlay.Nudge("forgotten text"), port.overlay.value)
        rows.value = listOf(older) // N1 erased the owning row; the older glance nudge is the newest now
        runCurrent()
        assertNull("the toast of a deleted row is cleared at once", port.overlay.value)
        assertEquals("Older survivor", port.latest.value?.text)
        advanceTimeBy(10_000); runCurrent()
        assertNull(port.overlay.value)
    }

    @Test fun aNewerNudgeStillReplacesTheToastItself() = runTest {
        val rows = MutableStateFlow(emptyList<NudgeEntity>())
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        rows.value = listOf(row("a", start + 1_000, "toast", text = "first")); runCurrent()
        rows.value = listOf(row("b", start + 2_000, "glance", text = "second"), row("a", start + 1_000, "toast", text = "first")); runCurrent()
        assertEquals("a newer row is not a disappeared owner", Overlay.Nudge("first"), port.overlay.value)
    }

    @Test fun deletingAnOlderToastOwnerWhileANewerRowIsNewestClearsTheToast() = runTest {
        val rows = MutableStateFlow(emptyList<NudgeEntity>())
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        val a = row("a", start + 1_000, "toast", text = "forgotten A")
        rows.value = listOf(a); runCurrent()
        assertEquals(Overlay.Nudge("forgotten A"), port.overlay.value)
        val b = row("b", start + 2_000, "glance", text = "newer B")
        rows.value = listOf(b, a); runCurrent()
        assertEquals(Overlay.Nudge("forgotten A"), port.overlay.value)
        rows.value = listOf(b); runCurrent() // forget A: B is newer, A is gone
        assertNull("the owner is absent from the rows: cleared although B is newer", port.overlay.value)
        assertEquals("newer B", port.latest.value?.text)
    }

    @Test fun anEqualTimestampDoesNotKeepTheDeletedOwnersToast() = runTest {
        val rows = MutableStateFlow(emptyList<NudgeEntity>())
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        val a = row("a", start + 1_000, "toast", text = "forgotten A"); val b = row("b", start + 1_000, "glance", text = "same time B")
        rows.value = listOf(a); runCurrent()
        rows.value = listOf(b, a); runCurrent()
        rows.value = listOf(b); runCurrent()
        assertNull(port.overlay.value)
    }

    @Test fun flagOffNeverClearsAToastByOwnerPresence() = runTest {
        val rows = MutableStateFlow(emptyList<NudgeEntity>())
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { false })
        rows.value = listOf(row("a", start + 1_000, "toast", text = "A")); runCurrent()
        rows.value = listOf(row("b", start + 2_000, "glance", text = "B")); runCurrent()
        assertEquals("v1: only the timeout, a redacted row or no row clears it", Overlay.Nudge("A"), port.overlay.value)
        rows.value = listOf(row("old", start - 5_000, "glance", text = "older")); runCurrent()
        assertEquals("v1: even an older newest row leaves it", Overlay.Nudge("A"), port.overlay.value)
        advanceTimeBy(8_100); runCurrent()
        assertNull(port.overlay.value)
    }

    @Test fun latestExpiresAfterSixHours() = runTest {
        val port = NudgePort(MutableStateFlow(listOf(row("n", start, "glance"))), backgroundScope, clock = { start + testScheduler.currentTime }, forgetV2 = { true })
        runCurrent()
        assertEquals("Milk is nearly out", port.latest.value?.text)
        advanceTimeBy(6 * 3_600_000L + 1); runCurrent()
        assertNull(port.latest.value)
    }
}
