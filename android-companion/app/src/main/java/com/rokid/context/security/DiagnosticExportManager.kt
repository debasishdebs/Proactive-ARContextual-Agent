package com.rokid.context.security

class DiagnosticExportManager {
    private val tokenTtlMs = 10L * 60L * 1000L

    fun tokenValid(used: Boolean, ageMs: Long): Boolean = !used && ageMs <= tokenTtlMs

    fun shouldDeleteDecryptedArtifact(shared: Boolean, sessionOpenMs: Long): Boolean {
        if (shared) return true
        return sessionOpenMs >= 5L * 60L * 1000L
    }
}
