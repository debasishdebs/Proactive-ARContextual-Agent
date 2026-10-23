package com.rokid.context.memory.canvas

/**
 * What one native tap binds (c8 #1): the job, the immutable bundle hash of its payload, the session generation and the confirmation expiry.
 * Plain data in `:services:memory` beside [HelperFence] so the job DAO (WP3) and the job port (WP1) can both take it without an upward module dependency.
 * The HMAC key, the tag and its verification are `:phone` only (WP2/WP6); [message] is the exact string the tag is computed over.
 */
data class ConfirmBinding(val jobId: String, val bundleHash: String, val sessionGen: Long, val expiresAtMs: Long) {
    fun message(): String = "$jobId|$bundleHash|$sessionGen|$expiresAtMs"
}
