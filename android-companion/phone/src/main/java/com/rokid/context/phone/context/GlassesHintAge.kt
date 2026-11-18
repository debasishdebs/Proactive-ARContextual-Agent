package com.rokid.context.phone.context

/**
 * How old a glasses motion hint was when it reached the phone (Codex v3 r3 P1-1). The hint's own stamp is the glasses' elapsedRealtime, a clock the phone cannot
 * compare with its own; but within one glasses session the difference (phone monotonic receipt minus glasses stamp) only grows by the transport delay, so the
 * smallest difference seen is the baseline and the excess over it is the age. The FIRST hint of a session (the baseline itself) has an unknown age, and so has
 * a reordered hint, one with no session, a hint older than [MAX_AGE_MS] and one that looks "from the future" (the baseline was itself delayed): an unknown age is never calm evidence.
 * Memory only, one number per session.
 */
class GlassesHintAge {
    private var session = 0L
    private var minOffset = 0L
    private var lastStamp = 0L
    private var have = false

    /** [sessionStartMs] of the hint (0 = unknown), its glasses stamp [glassesAtMs], and the phone's monotonic receipt time [monoNowMs]; the age in ms or null (unknown). */
    @Synchronized fun observe(sessionStartMs: Long, glassesAtMs: Long, monoNowMs: Long): Long? {
        if (sessionStartMs == 0L) return null
        val off = monoNowMs - glassesAtMs
        if (!have || sessionStartMs != session) { session = sessionStartMs; minOffset = off; lastStamp = glassesAtMs; have = true; return null }
        if (glassesAtMs <= lastStamp) return null
        lastStamp = glassesAtMs
        if (off < minOffset - FUTURE_SLACK_MS) { minOffset = off; return null }
        if (off < minOffset) minOffset = off
        val age = off - minOffset
        return if (age > MAX_AGE_MS) null else age
    }

    companion object {
        const val MAX_AGE_MS = 2 * 60_000L
        const val FUTURE_SLACK_MS = 5_000L
    }
}
