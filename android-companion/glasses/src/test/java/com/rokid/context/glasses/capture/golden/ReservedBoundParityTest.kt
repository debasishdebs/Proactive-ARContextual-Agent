package com.rokid.context.glasses.capture.golden

import com.rokid.context.capture.plan.reservedBoundMs
import com.rokid.context.core.model.context.ContextState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The golden recorder re-implements S83's reserved bound; the production function must give the same value for every input. */
class ReservedBoundParityTest {
    @Test fun productionBoundEqualsTheGoldenRecordersRule() {
        val m = DecisionGoldenRecorder::class.java.getDeclaredMethod("reservedBoundMs", ContextState::class.java, java.lang.Boolean.TYPE)
        m.isAccessible = true
        for (s in ContextState.entries) for (resting in listOf(true, false)) {
            assertEquals("$s resting=$resting", m.invoke(DecisionGoldenRecorder, s, resting) as Long, reservedBoundMs(s, resting))
        }
    }
}
