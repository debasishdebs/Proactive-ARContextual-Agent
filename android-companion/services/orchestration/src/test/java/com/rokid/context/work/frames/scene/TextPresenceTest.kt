package com.rokid.context.work.frames.scene

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextPresenceTest {
    @Test fun timeoutReturnsNull() = runBlocking {
        val tp = MlKitTextPresence(timeoutMs = 50L) { awaitCancellation() }
        assertNull(tp.words(ByteArray(4)))
    }

    @Test fun recognizerFailureReturnsNull() = runBlocking {
        val tp = MlKitTextPresence(timeoutMs = 500L) { error("no model") }
        assertNull(tp.words(ByteArray(4)))
    }

    @Test fun countsOnlyWordTokens() = runBlocking {
        // tokens with >= 2 letters: Hello, world, ok, Ünï, ab12 ; not: 123, a, x9, --, 5
        val tp = MlKitTextPresence(timeoutMs = 500L) { "Hello, world 123 a -- x9 ok\nÜnï 5 ab12" }
        assertEquals(5, tp.words(ByteArray(4)))
        assertEquals(0, MlKitTextPresence(timeoutMs = 500L) { "" }.words(ByteArray(4)))
    }
}
