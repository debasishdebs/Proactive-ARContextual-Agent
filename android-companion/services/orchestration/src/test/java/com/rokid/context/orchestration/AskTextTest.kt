package com.rokid.context.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fix round 1 (minors 1+2): the JVM's `\s` and `\p{Cntrl}` miss these, so each must be listed explicitly. */
class AskTextTest {
    @Test fun nextLineCollapses() = assertEquals("milk is low", AskText.collapse("milk\u0085is low"))
    @Test fun lineSeparatorCollapses() = assertEquals("milk is low", AskText.collapse("milk is low"))
    @Test fun paragraphSeparatorCollapses() = assertEquals("milk is low", AskText.collapse("milk is low"))
}
