package com.rokid.context.capture.replay

import com.rokid.context.capture.replay.ReplayFixtures.PRE_ROLL_END
import com.rokid.context.capture.replay.ReplayFixtures.SETTLED
import com.rokid.context.capture.replay.ReplayFixtures.START
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.TraceEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureSimulatorStreamTest {
    private val twelveHours = 12L * 3_600_000L

    /**
     * The input is a lazily generated 12 h trace (25 Hz gyro and accel plus a timer episode every ~5.3 s: over two million events, which materialised
     * would be well over 100 MB). The heap in use after a forced GC, sampled every 200,000 events while the simulator pulls the sequence, stays under a fixed bound.
     */
    @Test fun streamsWithoutMaterialising() {
        val end = START + twelveHours
        var emitted = 0L
        var baseline = -1L
        var peak = 0L
        fun used(): Long { System.gc(); return Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } }
        val seq: Sequence<TraceEvent> = sequence {
            ReplayFixtures.initial().forEach { yield(it) }
            var nextOpen = START + SETTLED - ReplayFixtures.leadAfter(0)
            var closes = 0
            var scene = 0
            val queue = ArrayDeque<TraceEvent>()
            var pr = false
            var t = START
            baseline = used()
            while (t < end) {
                if (!pr && t >= PRE_ROLL_END) { yield(TraceEvent.PreRollEnd(PRE_ROLL_END)); pr = true }
                while (nextOpen <= t) {
                    // one kept frame per episode keeps `msSinceKeyframe` short (past 10 minutes the policy would drop to its quiet cadence)
                    val ep = ReplayFixtures.timerEpisode(nextOpen)
                    scene++
                    queue += ep[0]; queue += ReplayFixtures.score(nextOpen + 520, scene.toLong(), scene); queue += ReplayFixtures.keep(nextOpen + 520, scene.toLong(), nextOpen + 500); queue += ep[1]
                    closes++
                    nextOpen += 800 + SETTLED - ReplayFixtures.leadAfter(closes)
                }
                while (queue.isNotEmpty() && queue.first().tMs <= t) yield(queue.removeFirst())
                yield(TraceEvent.Gyro(t, t, 0.01f, 0f, 0f)); yield(TraceEvent.Accel(t, t, 0f, 0f, 0f))
                emitted += 2
                if (emitted % 200_000L == 0L) peak = maxOf(peak, used() - baseline)
                t += 40
            }
            yield(TraceEvent.Close(end, "stop"))
        }
        val r = CaptureSimulator.replay(seq, ReplayFixtures.manifest(endMs = end))
        assertTrue(emitted > 2_000_000L, "emitted=$emitted")
        assertTrue(r.opens.size > 8_000, "opens=${r.opens.size}")
        assertEquals(0, r.failed, r.mismatches.toString())
        assertTrue(peak < 64L * 1024 * 1024, "peak retained heap ${peak / 1024 / 1024} MB while streaming ${emitted} events")
    }
}
