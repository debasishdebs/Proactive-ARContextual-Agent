package com.rokid.context.watch.motion

import com.rokid.context.core.model.context.WatchMotionCodec
import com.rokid.context.core.model.context.WatchMotionMessage
import com.rokid.context.core.model.context.WatchState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionSenderTest {
    private class Fake(var failFirst: Int = 0) : PhoneLink {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val times = mutableListOf<Long>()
        var now: () -> Long = { 0 }
        override suspend fun send(path: String, body: ByteArray): Boolean {
            times += now()
            sent += path to body
            if (failFirst > 0) { failFirst--; return false }
            return true
        }
    }

    private var t = 1_000_000L
    private val report = MotionReport(WatchState.WALKING, 0.8f, 900_000L)

    private fun sender(link: Fake, epoch: Long = 77L): MotionSender {
        link.now = { t }
        return MotionSender(link, { t }, { t += it }, epoch)
    }

    private fun decode(b: ByteArray) = assertNotNull(WatchMotionCodec.decode(b.decodeToString()))

    @Test fun wireRoundTripWithPcaSource() = runTest {
        val link = Fake()
        assertEquals(1, sender(link).send(report))
        val (path, body) = link.sent.single()
        assertEquals("/pca/motion", path)
        assertTrue(body.size <= WatchWire.MAX_BYTES)
        val m = decode(body)
        assertEquals(WatchMotionMessage(v = 1, seq = 1, state = WatchState.WALKING, confidence = 0.8f, changedAtMs = 900_000L, sentAtMs = 1_000_000L, source = "pca_watch", epoch = 77L), m)
        val json = body.decodeToString()
        assertTrue("\"source\":\"pca_watch\"" in json && "\"v\":1" in json)
    }

    @Test fun retriesWithBackoffAndSeqGrowsPerAttempt() = runTest {
        val link = Fake(failFirst = 2)
        assertEquals(3, sender(link).send(report))
        assertEquals(listOf(1L, 2L, 3L), link.sent.map { decode(it.second).seq })
        // 1.1 s then 2.2 s backoff between attempts
        assertEquals(listOf(1_100L, 2_200L), link.times.zipWithNext { a, b -> b - a })
    }

    @Test fun givesUpAfterSixTries() = runTest {
        val link = Fake(failFirst = 100)
        assertNull(sender(link).send(report))
        assertEquals(6, link.sent.size)
        val gaps = link.times.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1_100L, 2_200L, 4_400L, 8_800L, 17_600L), gaps)
        assertTrue(gaps.all { it <= MotionSender.MAX_BACKOFF_MS })
    }

    @Test fun consecutiveSendsAreAtLeast1100msApart() = runTest {
        val link = Fake()
        val s = sender(link)
        s.send(report)
        t += 200
        s.send(report.copy(state = WatchState.STILL))
        assertTrue(link.times[1] - link.times[0] >= 1_100L)
        assertEquals(listOf(1L, 2L), link.sent.map { decode(it.second).seq })
    }

    @Test fun neverCarriesStepsHeartRateOrLocation() = runTest {
        val link = Fake()
        sender(link).send(report)
        val keys = Regex("\"(\\w+)\":").findAll(link.sent.single().second.decodeToString()).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("v", "seq", "state", "confidence", "changedAtMs", "sentAtMs", "source", "epoch"), keys)
    }
}
