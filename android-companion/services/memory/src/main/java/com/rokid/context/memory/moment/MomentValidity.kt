package com.rokid.context.memory.moment

/**
 * What makes a STORED Moment valid (user ruling 2026-10-25). A Moment needs a timestamp (its id is a real second) and a LIVE photo; the only exception is the MARK Moment that carries an explicit
 * error reason (the photo-failed fallback: the mark could not take its photo and says why). A MARK Moment without a reason (a bare mark) is invalid. Exhaustive over [MomentType]: a new type must decide.
 */
object MomentValidity {
    /** The fallback-valid predicate: a MARK Moment with a non-empty error reason. */
    fun isFallbackMark(type: MomentType, errorReason: String?): Boolean = type == MomentType.MARK && !errorReason.isNullOrBlank()

    /** [hasLivePhoto]: at least one photo part is a live frame with a capture time. */
    fun isValid(momentId: String, type: MomentType, errorReason: String?, hasLivePhoto: Boolean): Boolean =
        MomentId.parse(momentId) != null && when (type) {
            MomentType.FRAME -> hasLivePhoto
            MomentType.MARK -> isFallbackMark(type, errorReason)
        }
}
