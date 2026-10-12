package com.rokid.context.prompt

import com.rokid.context.prompt.LineDiff.Kind.ADDED
import com.rokid.context.prompt.LineDiff.Kind.REMOVED
import com.rokid.context.prompt.LineDiff.Kind.SAME
import org.junit.Assert.assertEquals
import org.junit.Test

class LineDiffTest {
    @Test fun changedLineIsARemovalPlusAnAddition() = assertEquals(
        listOf(LineDiff.Line(SAME, "a"), LineDiff.Line(REMOVED, "b"), LineDiff.Line(ADDED, "B"), LineDiff.Line(SAME, "c"), LineDiff.Line(ADDED, "d")),
        LineDiff.diff("a\nb\nc", "a\nB\nc\nd"),
    )

    @Test fun identicalTextIsAllSame() = assertEquals(listOf(SAME, SAME), LineDiff.diff("x\ny", "x\ny").map { it.kind })
}
