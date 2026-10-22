package com.rokid.context.capture.plan

import com.rokid.context.core.model.PlanMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReceiverStateJsonTest {
    private val prev = ReceiverState(2, "p1", PlanMode.SHADOW, "{\"a\":1}", "abc123def456", null, null, "bc:7", null, emptyMap())
    private val full = ReceiverState(
        appliedGen = 5, phoneInstall = "p1", mode = PlanMode.TRIAL, planJson = "{\"x\":\"é\\\"\"}", configHash = "0123456789ab",
        trialStartElapsed = 123_456L, trialMaxMs = 3_600_000L, bootId = "bc:9", previous = prev,
        terminal = mapOf(3L to "auto:trial_end", 4L to "auto:ceiling"), effective = true, publishId = "pub-1",
        terminalMeta = mapOf(3L to TerminalMeta(PlanMode.TRIAL, "pub-0"), 4L to TerminalMeta(PlanMode.SHADOW, null)),
    )

    @Test fun roundTripIsExact() {
        assertEquals(full, ReceiverStateJson.decode(ReceiverStateJson.encode(full)))
        assertEquals(prev, ReceiverStateJson.decode(ReceiverStateJson.encode(prev)))
    }

    @Test fun olderFormWithoutTheNewFieldsDecodesWithDefaults() {
        val old = """{"appliedGen":4,"phoneInstall":"p1","mode":"TRIAL","planJson":null,"configHash":"0123456789ab","trialStartElapsed":10,"trialMaxMs":60000,"bootId":"bc:1","terminal":{"2":"auto:reboot"}}"""
        val s = ReceiverStateJson.decode(old)!!
        assertEquals(false, s.effective); assertNull(s.publishId); assertEquals(emptyMap(), s.terminalMeta); assertNull(s.previous)
        assertEquals(mapOf(2L to "auto:reboot"), s.terminal); assertEquals(PlanMode.TRIAL, s.mode)
    }

    @Test fun malformedOrUnknownIsNullNotACrash() {
        assertNull(ReceiverStateJson.decode(null)); assertNull(ReceiverStateJson.decode("")); assertNull(ReceiverStateJson.decode("{"))
        assertNull(ReceiverStateJson.decode("""{"appliedGen":"x"}"""))
        assertNull(ReceiverStateJson.decode("""{"appliedGen":1,"phoneInstall":"p","mode":"WARP","bootId":"b","terminal":{}}"""))
    }

    @Test fun bootIdChangesWithTheBootCountAndNeverWithTheClockWhenTheCountIsKnown() {
        assertNotEquals(BootId.of(7, 1_000_000, 5_000), BootId.of(8, 1_000_000, 5_000))
        assertEquals(BootId.of(7, 1_000_000, 5_000), BootId.of(7, 9_999_999, 777))
    }

    @Test fun bootIdFallsBackToTheBootInstantWhenTheCountIsUnreadable() {
        val a = BootId.of(-1, 10_000_000, 100_000)           // booted at 9_900_000
        val b = BootId.of(-1, 20_000_000, 100_000)           // booted at 19_900_000: another boot
        assertNotEquals(a, b)
        assertNotEquals(BootId.of(-1, 1_000_000, 1_000), BootId.of(7, 1_000_000, 1_000))
    }
}
