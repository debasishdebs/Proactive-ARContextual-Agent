package com.rokid.context.orchestration.nudge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.NudgeEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NudgeGatesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val hud = NudgeHud(QuietHours(22 * 60, 7 * 60), Verbosity.MEDIUM, zone)
    private var cap = 6
    private lateinit var gates: NudgeGates

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        gates = NudgeGates(MemoryGraph.get(ctx).db.nudgeGateDao()) { cap }
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    /**
     * Integration carry 9: the gate uses the phone's QuietWindow occurrence math (Stream A), not a local minute-of-day
     * match. Berlin falls back on 25 Oct 2026 (03:00 CEST -> 02:00 CET); quiet 00:00-02:30 ends at the first 02:30
     * (earlier offset), so the repeated 02:15 CET is outside the window, as the phone's pause set sees it.
     */
    @Test fun quietHoursFollowTheSharedOccurrenceAcrossAFallBack() = runBlocking {
        val berlin = ZoneId.of("Europe/Berlin")
        val h = NudgeHud(QuietHours(0, 150), Verbosity.MEDIUM, berlin)
        val secondQuarterPastTwo = java.time.Instant.parse("2026-10-25T01:15:00Z").toEpochMilli() // 02:15 CET
        val firstQuarterPastTwo = java.time.Instant.parse("2026-10-25T00:15:00Z").toEpochMilli() // 02:15 CEST
        assertEquals(Gate.QUIET_HOURS, gates.precheck(h, firstQuarterPastTwo))
        assertEquals(Gate.PASS, gates.precheck(h, secondQuarterPastTwo))
        assertEquals(
            com.rokid.context.core.model.QuietWindow.occurrenceAt(h.quietHours!!, secondQuarterPastTwo, berlin) != null,
            gates.precheck(h, secondQuarterPastTwo) == Gate.QUIET_HOURS,
        )
    }

    private suspend fun stored(id: String, ts: Long, text: String) =
        MemoryGraph.get(ctx).nudges.addIgnore(NudgeEntity(id, ts, text, "", "[]", 1, NudgeGates.hash(text), "glance", false))

    @Test fun quietHoursDropsNudge() = runBlocking {
        assertEquals(Gate.QUIET_HOURS, gates.check("Milk is low", hud, at(12, 23)))
        assertEquals(Gate.PASS, gates.check("Milk is low", hud, at(12, 21, 59)))
        assertEquals(Gate.PASS, gates.check("Milk is low", hud.copy(quietHours = null), at(12, 23)))
    }

    @Test fun capCountsFromLocalMidnight() = runBlocking {
        repeat(6) { stored("n$it", at(12, 23, 59), "old $it") }
        assertEquals(Gate.PASS, gates.check("new one", hud.copy(quietHours = null), at(13, 0, 1)))
        repeat(6) { stored("t$it", at(13, 0, 2), "today $it") }
        assertEquals(Gate.CAP_REACHED, gates.check("new one", hud.copy(quietHours = null), at(13, 0, 3)))
    }

    @Test fun duplicateTextWithinDayDropped() = runBlocking {
        stored("n1", at(12, 9), "Milk is low")
        assertEquals(Gate.DUPLICATE, gates.check("  milk   IS\nlow ", hud.copy(quietHours = null), at(12, 20)))
        assertEquals("a day later it may come back", Gate.PASS, gates.check("milk is low", hud.copy(quietHours = null), at(13, 9, 1)))
    }

    @Test fun capZeroIsOff() = runBlocking {
        cap = 0
        assertEquals(Gate.OFF, gates.check("anything", hud, at(12, 23)))
    }
}
