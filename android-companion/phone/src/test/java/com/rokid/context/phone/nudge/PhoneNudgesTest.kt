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
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime })
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
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime })
        rows.value = listOf(row("g", start + 10, "glance"))
        runCurrent()
        assertNull(port.overlay.value)
        assertEquals("Milk is nearly out", port.latest.value?.text)
    }

    @Test fun silentNudgeNotOnGlance() = runTest {
        val port = NudgePort(MutableStateFlow(listOf(row("s", start + 10, "phone"))), backgroundScope, clock = { start + testScheduler.currentTime })
        runCurrent()
        assertNull(port.latest.value)
        assertNull(port.overlay.value)
    }

    @Test fun redactionClearsLatest() = runTest {
        val rows = MutableStateFlow(listOf(row("n", start + 10, "toast")))
        val port = NudgePort(rows, backgroundScope, clock = { start + testScheduler.currentTime })
        runCurrent()
        assertEquals("Milk is nearly out", port.latest.value?.text)
        rows.value = listOf(row("n", start + 10, "toast", redacted = true))
        runCurrent()
        assertNull(port.latest.value)
        assertNull(port.overlay.value)
    }

    @Test fun latestExpiresAfterSixHours() = runTest {
        val port = NudgePort(MutableStateFlow(listOf(row("n", start, "glance"))), backgroundScope, clock = { start + testScheduler.currentTime })
        runCurrent()
        assertEquals("Milk is nearly out", port.latest.value?.text)
        advanceTimeBy(6 * 3_600_000L + 1); runCurrent()
        assertNull(port.latest.value)
    }
}
