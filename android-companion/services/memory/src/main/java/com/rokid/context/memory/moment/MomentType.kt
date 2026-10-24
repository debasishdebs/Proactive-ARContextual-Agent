package com.rokid.context.memory.moment

/**
 * What ANCHORS a Moment (design decisions 8.1). Derived deterministically from the parts at build time by [of] and stored as a column; it is NOT given a default anywhere, and every `when`
 * over it is exhaustive, so the compiler lists every reader that must decide what a Moment without a photo means. AUDIO is reserved (a later anchor): it is not a value yet so no branch
 * has to invent a meaning for it.
 */
enum class MomentType {
    /** At least one photo. The Moment of every version before v13. */
    FRAME,
    /** Born from a mark alone: no photo; sensors are optional (only when a trace covers the second). */
    MARK;

    companion object {
        /** FRAME when the second holds a photo, else MARK when it holds a mark; a second with neither is not a Moment (null). */
        fun of(frames: Int, marks: Int): MomentType? = when {
            frames > 0 -> FRAME
            marks > 0 -> MARK
            else -> null
        }
    }
}

/**
 * Why a MARK Moment has no photo, as a content-free code. Only what a cheap, reliable source can say is ever written: today nothing the phone keeps per second tells why capture was
 * silent (the pause state is live, not stored), so every MARK Moment says [UNKNOWN]. Later values (paused by battery, user or a persisted pause; capture off; no trace) are additive: the
 * column holds the name.
 */
enum class NoPhotoReason { UNKNOWN }
