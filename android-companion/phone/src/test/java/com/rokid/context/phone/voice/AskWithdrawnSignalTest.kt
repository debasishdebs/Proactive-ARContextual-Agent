package com.rokid.context.phone.voice

import com.rokid.context.phone.ui.Fixtures
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Codex r21/r22: when the HUD's answer is withdrawn (its stored ask redacted, or deleted). */
class AskWithdrawnSignalTest {
    private val row = Fixtures.ask("a1", 1)

    /** The r22 repro: the snapshot read the row, Delete all ran before the watcher's first read, so the watcher's first read is null. */
    @Test fun anAskDeletedBetweenTheSnapshotAndTheWatchersFirstReadIsWithdrawn() = runBlocking {
        assertEquals(listOf(true), VoiceGraph.withdrawnSignal(flowOf(null), existed = true).toList())
    }

    @Test fun anAskNotYetStoredIsNotWithdrawnUntilItExistedAndWentAway() = runBlocking {
        assertEquals(listOf(false), VoiceGraph.withdrawnSignal(flowOf(null), existed = false).toList())
        assertEquals(listOf(false, false, true), VoiceGraph.withdrawnSignal(flowOf(null, row, null), existed = false).toList())
    }

    @Test fun aRedactedAskIsWithdrawn() = runBlocking {
        assertEquals(listOf(false, true), VoiceGraph.withdrawnSignal(flowOf(row, row.copy(redacted = true)), existed = true).toList())
    }
}
