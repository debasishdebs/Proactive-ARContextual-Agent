package com.rokid.context.orchestration.proactive

import com.rokid.context.orchestration.proactive.generators.MarkInfo
import com.rokid.context.orchestration.proactive.generators.PinInfo
import com.rokid.context.orchestration.proactive.generators.PlaceHistorySource
import com.rokid.context.orchestration.proactive.generators.PlaceVisit

class FakeHistory(var visits: List<PlaceVisit> = emptyList(), var pins: List<PinInfo> = emptyList(), var marks: List<MarkInfo> = emptyList()) : PlaceHistorySource {
    override suspend fun visits(untilMs: Long, limit: Int) = visits.filter { it.tsMs <= untilMs }.sortedByDescending { it.tsMs }.take(limit)
    override suspend fun pins() = pins
    override suspend fun marksOnFrames(frameIds: Collection<String>) = marks.filter { it.frameId in frameIds }
}

const val DAY = 24 * 3_600_000L
