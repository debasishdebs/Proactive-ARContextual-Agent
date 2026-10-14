package com.rokid.context.glasses.capture.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraOwnerTest {
    @Test fun exclusion() = runTest {
        val owner = CameraOwner()
        val gate = CompletableDeferred<Unit>()
        val log = mutableListOf<String>()
        val a = async { owner.acquire("session") { log += "a-start"; gate.await(); log += "a-end" } }
        runCurrent()
        val b = async { owner.acquire("fixed") { log += "b" } }
        runCurrent()
        assertEquals("session", owner.current)
        assertEquals(listOf("a-start"), log)
        gate.complete(Unit)
        a.await(); b.await()
        assertEquals(listOf("a-start", "a-end", "b"), log)
        assertNull(owner.current)
    }

    @Test fun releasesOnException() = runTest {
        val owner = CameraOwner()
        try { owner.acquire("session") { error("boom") } } catch (_: IllegalStateException) { }
        assertNull(owner.current)
        assertEquals(7, owner.acquire("fixed") { 7 })
        assertTrue(owner.current == null)
    }
}
