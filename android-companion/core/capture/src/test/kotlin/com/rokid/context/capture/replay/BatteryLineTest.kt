package com.rokid.context.capture.replay

import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryLineTest {
    @Test fun a3Hours3_40() {                       // 2.4 s/min -> 0.49 %/min -> 3.40 h
        assertEquals(0.49, BatteryLine.pctPerMin(2.4), 0.001)
        assertEquals(3.40, BatteryLine.hours(2.4), 0.01)
    }
    @Test fun a2Hours2_78() {                       // 7 s/min -> 0.60 %/min -> 2.78 h
        assertEquals(0.60, BatteryLine.pctPerMin(7.0), 0.001)
        assertEquals(2.78, BatteryLine.hours(7.0), 0.01)
    }
    @Test fun floorIsTheZeroOpenPoint() { assertEquals(0.433, BatteryLine.pctPerMin(0.0), 1e-12) }
    @Test fun hoursAreOneHundredOverPctPerMinOverSixty() { assertEquals(100.0 / 0.5 / 60.0, BatteryLine.hoursFromFull(0.5), 1e-12) }
    @Test fun constantsAreExactlyTheMeasuredLine() {
        assertEquals(0.49036, BatteryLine.pctPerMin(2.4), 1e-12)               // 0.433 + 0.0239 * 2.4
        assertEquals(0.6003, BatteryLine.pctPerMin(7.0), 1e-12)                // 0.433 + 0.0239 * 7
        assertEquals(3.398863, BatteryLine.hours(2.4), 1e-6)
        assertEquals(2.776, BatteryLine.hours(7.0), 1e-3)
    }
}
