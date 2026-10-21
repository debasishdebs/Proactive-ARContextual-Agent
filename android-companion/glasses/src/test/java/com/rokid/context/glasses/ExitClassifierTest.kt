package com.rokid.context.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** S14: a force-stop is Rokid or other only when its caller is readable and older than the exit; stale and non-force-stop exits are ignored. */
class ExitClassifierTest {
    private val lastAlive = 1_000_000L
    private val now = lastAlive + 40_000L
    private val at = lastAlive + 30_000L
    private fun facts(desc: String? = "force stop by pid from pid 4242", user: Boolean = true, ts: Long = at) = ExitFacts(user, desc, ts)
    private fun kind(f: ExitFacts, caller: (Int) -> CallerInfo?) = ExitClassifier.classify(f, lastAlive, now, caller)

    @Test fun rokidCallerIsRokid() =
        assertEquals(ExitKind.ROKID, kind(facts()) { if (it == 4242) CallerInfo("com.rokid.os.sprite.assistserver", true) else null })

    @Test fun shellCallerIsOther() =
        assertEquals(ExitKind.OTHER, kind(facts()) { CallerInfo("com.android.shell", true) })

    @Test fun hiddenPidIsUnknown() = assertEquals(ExitKind.UNKNOWN, kind(facts()) { null })

    @Test fun reusedPidIsUnknown() =
        assertEquals(ExitKind.UNKNOWN, kind(facts()) { CallerInfo("com.rokid.os.sprite.assistserver", startedBeforeExit = false) })

    @Test fun noPidIsUnknown() = assertEquals(ExitKind.UNKNOWN, kind(facts(desc = "something else")) { CallerInfo("com.rokid.x", true) })

    @Test fun nullDescriptionIsUnknown() = assertEquals(ExitKind.UNKNOWN, kind(facts(desc = null)) { CallerInfo("com.rokid.x", true) })

    @Test fun notUserRequestedIsIgnored() = assertNull(kind(facts(user = false)) { CallerInfo("com.rokid.x", true) })

    @Test fun staleExitIgnored() {
        val c = { _: Int -> CallerInfo("com.rokid.x", true) }
        assertNull(kind(facts(ts = lastAlive - 5_000), c), "the window is open at lastAlive - 5 s")
        assertEquals(ExitKind.ROKID, kind(facts(ts = lastAlive - 4_999), c))
        assertEquals(ExitKind.ROKID, kind(facts(ts = now), c))
        assertNull(kind(facts(ts = now + 1), c))
    }
}
