package com.rokid.context.work.frames.place

import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.ExtractPhoto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPhotoTest {
    private fun photo(id: String) = ExtractPhoto(id, "d", ModelImageInput("image/jpeg", "AAAA"))

    @Test fun twoConsumersShareOneLoad() = runTest {
        var loads = 0
        val sp = SharedPhoto { id -> loads++; delay(100); photo(id) }
        val a = async { sp.get("f1") }; val b = async { sp.get("f1") }
        advanceUntilIdle()
        assertEquals(1, loads); assertSame(a.await(), b.await())
    }

    @Test fun aNewFrameReloadsAndClearForgets() = runTest {
        var loads = 0
        val sp = SharedPhoto { id -> loads++; photo(id) }
        sp.get("f1"); sp.get("f1"); assertEquals(1, loads)
        sp.get("f2"); assertEquals(2, loads)
        sp.clear(); sp.get("f2"); assertEquals(3, loads)
    }

    @Test fun anErrorIsNoPhotoNotAHangAndIsNotRetriedInTheAttempt() = runTest {
        var loads = 0
        val sp = SharedPhoto { loads++; throw OutOfMemoryError() }
        assertNull(sp.get("f1")); assertNull(sp.get("f1")); assertEquals(1, loads)
    }

    @Test fun aLoadThatEndsAfterClearIsNotKept() = runTest {
        var loads = 0
        val sp = SharedPhoto { id -> loads++; delay(100); photo(id) }
        val a = async { sp.get("f1") }
        advanceTimeBy(50); sp.clear(); advanceUntilIdle(); a.await()
        sp.get("f1"); assertEquals(2, loads)
    }
}
