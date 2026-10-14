package com.rokid.context.ai.local

import java.io.File
import java.security.MessageDigest

/** Size first (cheap), then a streaming SHA-256, so a 37 MB model never sits in memory. */
object ModelFileVerifier {
    fun verify(file: File, bytes: Long, sha256: String): Boolean {
        if (!file.isFile || file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER).use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(sha256, ignoreCase = true)
    }

    private const val BUFFER = 64 * 1024
}
