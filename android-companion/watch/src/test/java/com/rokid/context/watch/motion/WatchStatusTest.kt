package com.rokid.context.watch.motion

import com.rokid.context.core.model.context.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStatusTest {
    private val sent = MotionMemory(reported = WatchState.WALKING, lastSentMs = 1_000)

    @Test fun linkStatus() {
        assertEquals(LinkStatus.OFF, WatchStatus.link(false, true, sent))
        assertEquals(LinkStatus.PERMISSION_NEEDED, WatchStatus.link(true, false, sent))
        assertEquals(LinkStatus.WAITING, WatchStatus.link(true, true, MotionMemory()))
        assertEquals(LinkStatus.PHONE_NOT_REACHABLE, WatchStatus.link(true, true, sent.copy(unsent = true)))
        assertEquals(LinkStatus.SENDING, WatchStatus.link(true, true, sent))
    }

    @Test fun afterASuccessfulSendTheChipIsNotWaiting() {
        val m = MotionStateDetector.markSent(MotionMemory(reported = WatchState.STILL, unsent = true), 5_000)
        assertEquals(LinkStatus.SENDING, WatchStatus.link(true, true, m))
        // even if the stored reported state was lost, a recorded send is not "waiting for motion"
        assertEquals(LinkStatus.SENDING, WatchStatus.link(true, true, MotionMemory(lastSentMs = 5_000, lastSentState = WatchState.STILL)))
    }

    @Test fun lastSentLine() {
        val t = { ms: Long -> "T$ms" }
        assertEquals(null, WatchStatus.lastSent(MotionMemory(), t))
        assertEquals(null, WatchStatus.lastSent(MotionMemory(reported = WatchState.WALKING, unsent = true), t))
        val m = MotionStateDetector.markSent(MotionMemory(reported = WatchState.WALKING, unsent = true), 7_000)
        assertEquals("Last sent: WALKING T7000", WatchStatus.lastSent(m, t))
        // a newer, still unsent state does not rewrite what was last sent
        val n = m.copy(reported = WatchState.RUNNING, unsent = true)
        assertEquals("Last sent: WALKING T7000", WatchStatus.lastSent(n, t))
    }

    @Test fun sentAgo() {
        assertEquals("not sent yet", WatchStatus.sentAgo(0, 5))
        assertEquals("sent just now", WatchStatus.sentAgo(1_000, 30_000))
        assertEquals("sent 2 min ago", WatchStatus.sentAgo(0 + 1, 150_000))
    }

    @Test fun everyStateHasALabel() = WatchState.values().forEach { assert(WatchStatus.stateLabel(it).isNotBlank()) }

    @Test fun friendlyLabels() {
        assertEquals("Driving", WatchStatus.stateLabel(WatchState.IN_VEHICLE))
        assertEquals("Still", WatchStatus.stateLabel(WatchState.STILL))
        assertEquals("Asleep", WatchStatus.stateLabel(WatchState.ASLEEP))
        assertEquals(WatchState.values().size, WatchState.values().map { WatchStatus.stateLabel(it) }.toSet().size)
    }

    @Test fun everyStateHasADistinctAccentAndIcon() {
        val styles = WatchState.values().map { WatchLook.style(it) }
        assertEquals(styles.size, styles.map { it.accent }.toSet().size)
        assertEquals(styles.size, styles.map { it.icon }.toSet().size)
        assertEquals(StateIcon.values().size, styles.map { it.icon }.toSet().size)
    }

    @Test fun accentsMatchTheBrief() {
        fun hue(s: WatchState): Float {
            val c = WatchLook.style(s).accent.toInt()
            val hsv = FloatArray(3)
            val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            val h = when { d == 0f -> 0f; mx == r -> 60 * (((g - b) / d) % 6); mx == g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
            hsv[0] = if (h < 0) h + 360 else h
            return hsv[0]
        }
        assert(hue(WatchState.WALKING) in 100f..140f)   // green
        assert(hue(WatchState.RUNNING) in 15f..35f)     // orange
        assert(hue(WatchState.CYCLING) in 160f..180f)   // teal
        assert(hue(WatchState.IN_VEHICLE) in 40f..55f)  // amber
        assert(hue(WatchState.EXERCISE).let { it < 5f || it > 355f }) // red
        assert(hue(WatchState.ASLEEP) in 235f..255f)    // indigo
        assert(hue(WatchState.STILL) in 205f..225f)     // slate blue
    }

    @Test fun linkLinesAndFixes() {
        assertEquals("Sending to phone", WatchStatus.linkText(LinkStatus.SENDING))
        assertEquals("Phone not reachable", WatchStatus.linkText(LinkStatus.PHONE_NOT_REACHABLE))
        assertEquals("Permission needed", WatchStatus.linkText(LinkStatus.PERMISSION_NEEDED))
        assertEquals(WatchStatus.Fix.GRANT_PERMISSION, WatchStatus.fix(LinkStatus.PERMISSION_NEEDED))
        assertEquals(WatchStatus.Fix.TURN_ON, WatchStatus.fix(LinkStatus.OFF))
        assertEquals(null, WatchStatus.fix(LinkStatus.SENDING))
        assertEquals(null, WatchStatus.fix(LinkStatus.PHONE_NOT_REACHABLE))
        LinkStatus.values().forEach { assert(WatchStatus.linkText(it).isNotBlank()) }
    }
}
