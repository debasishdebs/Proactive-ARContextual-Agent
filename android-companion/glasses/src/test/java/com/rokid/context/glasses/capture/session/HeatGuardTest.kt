package com.rokid.context.glasses.capture.session

import kotlin.test.Test
import kotlin.test.assertEquals

class HeatGuardTest {
    private var now = 0L
    private var reading = HeatReading(0, Float.NaN, 30f)
    private val guard = HeatGuard({ reading }, { now })

    private fun poll(status: Int = 0, temp: Float = 30f, headroom: Float = Float.NaN): HeatLevel {
        reading = HeatReading(status, headroom, temp)
        guard.poll()
        return guard.level.value
    }

    @Test fun moderateSlows() = assertEquals(HeatLevel.SLOW, poll(status = 3))
    @Test fun battery40Slows() = assertEquals(HeatLevel.SLOW, poll(temp = 40f))
    @Test fun severeStops() = assertEquals(HeatLevel.STOP, poll(status = 4))
    @Test fun battery44Stops() = assertEquals(HeatLevel.STOP, poll(temp = 44f))

    @Test fun resumeNeedsTwoMinutesCool() {
        poll(status = 4)
        now = 30_000; assertEquals(HeatLevel.STOP, poll(status = 2, temp = 37f)) // cool clock starts
        now = 120_000; assertEquals(HeatLevel.STOP, poll(status = 2, temp = 37f)) // 90 s
        now = 130_000; assertEquals(HeatLevel.STOP, poll(status = 2, temp = 39f)) // too warm: clock resets
        now = 160_000; assertEquals(HeatLevel.STOP, poll(status = 2, temp = 36f))
        now = 270_000; assertEquals(HeatLevel.STOP, poll(status = 2, temp = 36f)) // 110 s
        now = 280_000; assertEquals(HeatLevel.OK, poll(status = 2, temp = 36f)) // 120 s
    }

    @Test fun nanHeadroomIgnored() {
        assertEquals(HeatLevel.OK, poll(headroom = Float.NaN))
        assertEquals(null, guard.lastHeadroom)
        assertEquals(HeatLevel.OK, poll(headroom = 0.99f)) // a high headroom reading never stops or slows by itself
        assertEquals(0.99f, guard.lastHeadroom)
        assertEquals(HeatLevel.OK, poll(headroom = Float.NaN))
        assertEquals(0.99f, guard.lastHeadroom)
    }

    @Test fun stopHoldsWithoutABatteryTemperature() {
        poll(status = 4)
        now = 10_000; assertEquals(HeatLevel.STOP, poll(status = 0, temp = Float.NaN))
        now = 200_000; assertEquals(HeatLevel.STOP, poll(status = 0, temp = Float.NaN)) // unreadable: never counts as cool
        now = 210_000; assertEquals(HeatLevel.STOP, poll(status = 0, temp = 36f)) // cool clock starts only now
        now = 330_000; assertEquals(HeatLevel.OK, poll(status = 0, temp = 36f))
    }

    @Test fun anUnreadableBatteryNeverStartsAStop() = assertEquals(HeatLevel.OK, poll(status = 0, temp = Float.NaN))

    @Test fun stopFollowedByModerateStaysStop() {
        poll(status = 4)
        now = 30_000; assertEquals(HeatLevel.STOP, poll(status = 3, temp = 30f))
        now = 400_000; assertEquals(HeatLevel.STOP, poll(status = 3, temp = 30f))
    }

    @Test fun severeWithNanHeadroomStillStops() = assertEquals(HeatLevel.STOP, poll(status = 4, headroom = Float.NaN))

    @Test fun aClockThatWentBackRestartsTheCoolDown() {
        now = 500_000; poll(status = 4)
        now = 510_000; poll(status = 0, temp = 30f)        // since = 510 s
        now = 100_000; assertEquals(HeatLevel.STOP, poll(status = 0, temp = 30f)) // went back: restart
        now = 219_000; assertEquals(HeatLevel.STOP, poll(status = 0, temp = 30f))
        now = 220_000; assertEquals(HeatLevel.OK, poll(status = 0, temp = 30f))
    }

    @Test fun theRawLastReadingIsKeptForDiagnostics() {
        assertEquals(null, guard.lastReading)
        poll(status = 0, temp = 31f, headroom = Float.NaN)
        assertEquals(true, guard.lastReading!!.headroom.isNaN()) // NaN is recorded as NaN, not dropped
        assertEquals(31f, guard.lastReading!!.batteryTempC)
    }

    @Test fun pollerReadsEvery30sIncludingWhileStop() {
        var t = 0L
        var reads = 0
        val g = HeatGuard({ reads++; HeatReading(4, Float.NaN, 30f) }, { t })
        val p = HeatPoller(g, { t })
        var polled = 0
        for (sec in 0..95) { t = sec * 1_000L; if (p.tick()) polled++ }
        assertEquals(HeatLevel.STOP, g.level.value)
        assertEquals(4, polled) // 0, 30, 60, 90 s
        assertEquals(4, reads)
        t = 5_000; assertEquals(true, p.tick()) // a clock that went back polls at once
    }
}
