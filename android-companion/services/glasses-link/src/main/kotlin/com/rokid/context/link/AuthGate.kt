package com.rokid.context.link

/** Pure bookkeeping for the single in-flight Hi Rokid authorization attempt (ported from Live Fit). */
class AuthGate(private val timeoutMs: Long = AUTH_TIMEOUT_MS) {
    var authorized = false
        private set
    private var inFlight = false
    private var startedAt = 0L
    private var cancelled = false // closed by the owner: results of the cancelled attempt are ignored until the next begin
    private var rejected = false // the SDK rejected a token since the last started session

    /** Review hub-wp2 r5 P1: the origin of the attempt (the phone's automatic open), kept here and not in the mutable session policy. */
    var automatic = false
        private set
    /** The attempt's timeout fired: a result that still arrives is late. */
    var expired = false
        private set

    /** True when the caller should launch the authorization UI now (none is in flight, or the last one is stale). A user open joining an automatic attempt takes the origin over. */
    fun begin(now: Long, automatic: Boolean = false): Boolean {
        if (inFlight && now - startedAt < timeoutMs) { if (!automatic) this.automatic = false; return false }
        cancelled = false
        expired = false
        this.automatic = automatic
        inFlight = true
        startedAt = now
        return true
    }

    /** Drops an attempt older than the timeout (e.g. before a manual connect). */
    fun clearStale(now: Long) { if (inFlight && now - startedAt >= timeoutMs) inFlight = false }

    /** The owner closed the link: drop the attempt and ignore its late result. */
    fun cancel() { inFlight = false; cancelled = true }

    /** The timeout fired without any result. */
    fun expire() { inFlight = false; expired = true }

    /** Returns true if this result should be applied; late/duplicate reports for a resolved attempt are ignored. */
    fun result(ok: Boolean): Boolean {
        if (cancelled || (authorized && !inFlight)) return false
        inFlight = false
        if (ok) authorized = true
        return true
    }

    /**
     * The SDK rejected the saved token (TOKEN_EXPIRED / NOT_AUTHENTICATED): authorization is gone. True when the caller
     * should re-authorize; false when a token was already rejected since the last started session (no relaunch loop).
     */
    fun tokenRejected(): Boolean {
        authorized = false
        inFlight = false
        return !rejected.also { rejected = true }
    }

    /** A session started with the current token: a later rejection may re-authorize again. */
    fun sessionStarted() { rejected = false }

    companion object { const val AUTH_TIMEOUT_MS = 30_000L }
}
