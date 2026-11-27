package com.rokid.context.phone.place

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbedNowTextTest {
    @Test fun neverStartedShowsNothing() = assertNull(EmbedNowText.line(null, 0, 0, 0))
    @Test fun queuedAndWaiting() {
        assertEquals("Queued", EmbedNowText.line(WorkInfo.State.ENQUEUED, 0, 0, 5)?.text)
        assertEquals(true, EmbedNowText.line(WorkInfo.State.ENQUEUED, 2, 0, 5)?.text?.startsWith("Waiting"))
    }
    @Test fun runningHasDeterminateProgress() {
        val l = EmbedNowText.line(WorkInfo.State.RUNNING, 0, 3, 12)!!
        assertEquals("Embedding photos: 3 of 12", l.text)
        assertEquals(0.25f, l.progress!!, 0.001f)
    }
    @Test fun noPhotosFetched() {
        assertEquals(EmbedNowText.NO_PHOTOS, EmbedNowText.line(WorkInfo.State.SUCCEEDED, 0, 0, 0)?.text)
    }
    @Test fun doneAndFailed() {
        assertEquals("Done: 12 embedded", EmbedNowText.line(WorkInfo.State.SUCCEEDED, 0, 12, 12)?.text)
        assertEquals("Done: 4 embedded of 12 fetched", EmbedNowText.line(WorkInfo.State.SUCCEEDED, 0, 4, 12)?.text)
        assertEquals("Stopped: embedding failed", EmbedNowText.line(WorkInfo.State.FAILED, 0, 0, 3)?.text)
    }
}
