package com.rokid.context.ai.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelFileVerifierTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun verifierAcceptsMatchAndRejectsSizeOrHashMismatch() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val f = tmp.newFile("m.gguf").apply { writeBytes(data) }
        assertTrue(ModelFileVerifier.verify(f, data.size.toLong(), sha))
        assertTrue("hex case does not matter", ModelFileVerifier.verify(f, data.size.toLong(), sha.uppercase()))
        assertFalse("size mismatch", ModelFileVerifier.verify(f, data.size + 1L, sha))
        assertFalse("hash mismatch", ModelFileVerifier.verify(f, data.size.toLong(), sha.replaceRange(0, 1, if (sha[0] == '0') "1" else "0")))
        assertFalse("missing file", ModelFileVerifier.verify(tmp.root.resolve("absent"), data.size.toLong(), sha))
    }
}
