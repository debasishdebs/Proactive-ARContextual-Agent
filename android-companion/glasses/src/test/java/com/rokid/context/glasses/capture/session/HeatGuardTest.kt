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
}
