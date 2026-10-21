package com.rokid.context.core.model.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchMotionMessageTest {
    private val sample = """{"v":1,"seq":42,"state":"WALKING","confidence":0.9,"changedAtMs":1761012345678,"sentAtMs":1761012345900,"source":"galaxy_watch"}"""

    @Test fun decodesSpecSample() {
        val m = assertNotNull(WatchMotionCodec.decode(sample))
        assertEquals(42L, m.seq); assertEquals(WatchState.WALKING, m.state); assertEquals(0.9f, m.confidence)
        assertEquals(1761012345678L, m.changedAtMs); assertEquals("galaxy_watch", m.source)
    }

    @Test fun unknownStateIsUnknown() {
        assertEquals(WatchState.UNKNOWN, assertNotNull(WatchMotionCodec.decode("""{"v":1,"seq":1,"state":"SWIMMING"}""")).state)
    }

    @Test fun otherVersionIsNull() = assertNull(WatchMotionCodec.decode("""{"v":2,"seq":1,"state":"STILL"}"""))

    @Test fun confidenceIsClamped() {
        assertEquals(1.0f, assertNotNull(WatchMotionCodec.decode("""{"v":1,"seq":1,"confidence":3}""")).confidence)
        assertEquals(0.0f, assertNotNull(WatchMotionCodec.decode("""{"v":1,"seq":1,"confidence":-2}""")).confidence)
    }

    @Test fun malformedIsNull() {
        assertNull(WatchMotionCodec.decode("{"))
        assertNull(WatchMotionCodec.decode("""{"v":1}"""))
    }
}
