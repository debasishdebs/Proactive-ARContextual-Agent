package com.rokid.context.phone.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDropLogTest {
    private var now = 1_000_000L
    private val lines = ArrayList<String>()
    private val log = WatchDropLog(60_000L, { now }) { lines += it }

    @Test fun firstDropLogsOneLineWithCountOne() {
        log.drop("stale_seq")
        assertEquals(listOf("watch drop reason=stale_seq n=1"), lines)
    }

    @Test fun repeatsWithinAMinuteAreCountedNotLoggedThenSummarised() {
        repeat(5) { log.drop("rate"); now += 1_000 }
        assertEquals(1, lines.size)
        now += 60_000
        log.drop("rate")
        assertEquals(listOf("watch drop reason=rate n=1", "watch drop reason=rate n=5"), lines)
    }

    @Test fun eachReasonHasItsOwnWindow() {
        log.drop("rate"); log.drop("stale_seq"); log.drop("rate")
        assertEquals(listOf("watch drop reason=rate n=1", "watch drop reason=stale_seq n=1"), lines)
    }

    @Test fun internalReasonsMapToTheFixedTokenSet() {
        val m = mapOf(
            "not_enabled" to "source_off", "source_off" to "source_off", "hub_off" to "hub_down", "version" to "bad_version",
            "bad_source" to "wrong_source", "too_big" to "malformed", "malformed" to "malformed", "bad_path" to "bad_path",
            "stale_seq" to "stale_seq", "rate" to "rate", "anything with a payload {\"x\":1}" to "malformed",
        )
        m.forEach { (k, v) -> assertEquals(v, WatchDropLog.token(k)); assertTrue(WatchDropLog.token(k) in WatchDropLog.TOKENS) }
    }

    @Test fun aSinkFailureNeverEscapes() {
        WatchDropLog(60_000L, { now }) { error("boom") }.drop("rate")
    }

    @Test fun everyDropPathOfTheLinkLogsOnceWithAFixedTokenOnly() {
        val out = ArrayList<String>()
        val link = WatchLinkState(WatchDropLog(60_000L, { now }) { out += it })
        val i = WatchMotionIntake(enabled = { true }, clock = { now }, sink = { }, onReject = { link.onDrop(it) })
        fun msg(seq: Long, source: String = "pca_watch", v: Int = 1) =
            """{"v":$v,"epoch":5,"seq":$seq,"state":"WALKING","confidence":0.8,"changedAtMs":1,"sentAtMs":2,"source":"$source"}""".toByteArray()
        PcaWatchLink.handle("/other", msg(1), true, i, link)
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(1), false, i, link)
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(1), true, null, link)
        PcaWatchLink.handle(PcaWatchLink.PATH, "nope".toByteArray(), true, i, link)
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(1, v = 2), true, i, link)
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(1, source = "x"), true, i, link)
        assertTrue(PcaWatchLink.handle(PcaWatchLink.PATH, msg(3), true, i, link).ok)
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(3), true, i, link)
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(2), true, i, link)
        val reasons = out.map { it.substringAfter("reason=").substringBefore(" ") }
        assertEquals(listOf("bad_path", "source_off", "hub_down", "malformed", "bad_version", "wrong_source", "stale_seq"), reasons)
        out.forEach { assertTrue(it, Regex("watch drop reason=[a-z_]+ n=\\d+").matches(it)) }
        PcaWatchLink.handle(PcaWatchLink.PATH, msg(1), true, i, link) // second stale_seq inside the minute: counted, not logged
        assertEquals(7, out.size)
        assertEquals(3, link.snapshot.value.drops["stale_seq"])
    }
}
