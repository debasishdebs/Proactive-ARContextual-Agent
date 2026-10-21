package com.rokid.context.glasses.capture.session

/** The phone's in-use state from `pc_phone_ctx`, debounced: it counts as in use only after [DEBOUNCE_MS] of continuous use. */
class PhoneUse {
    @Volatile private var sinceMs = -1L

    fun onRemote(inUse: Boolean, nowMs: Long) {
        if (!inUse) sinceMs = -1L else if (sinceMs < 0) sinceMs = nowMs
    }

    fun reset() { sinceMs = -1L }

    fun active(nowMs: Long): Boolean { val s = sinceMs; return s >= 0 && nowMs - s >= DEBOUNCE_MS }

    companion object { const val DEBOUNCE_MS = 3_000L }
}
