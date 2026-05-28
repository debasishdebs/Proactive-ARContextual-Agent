package com.rokid.context.security

class QuarantineManager(
    private val ttlMillis: Long = 30L * 24L * 60L * 60L * 1000L,
) {
    fun isExpired(quarantinedAtMs: Long, nowMs: Long): Boolean = nowMs - quarantinedAtMs >= ttlMillis
}
