package com.rokid.context.data.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.domain.queue.QueuePriority
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** T21 review m11: a RUNNING row that TTL-expires mid-run stays EXPIRED; a late outcome write never revives it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueMarkGuardTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { QueueRuntimeStore.clearAll(ctx); AppConfigStore.setPipelineEnabled(ctx, true) }
    @After fun tearDown() = QueueRuntimeStore.clearAll(ctx)

    private fun expiredWhileRunning(): String {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("x", "en", manual = true), personaSnapshot = "p", ttlHours = 1)
        val id = QueueRuntimeStore.claim(ctx)!!.job.id
        assertEquals(QueueRuntimeStore.STATE_RUNNING, state())
        QueueRuntimeStore.snapshot(ctx, nowEpochMs = System.currentTimeMillis() + 2 * 3_600_000L) // normalizes: TTL passed
        assertEquals(QueueRuntimeStore.STATE_EXPIRED, QueueRuntimeStore.recent(ctx, 1).single().state)
        return id
    }

    private fun state() = QueueRuntimeStore.recent(ctx, 1).single().state

    @Test fun markDeferredDoesNotReviveAnExpiredRow() {
        val id = expiredWhileRunning()
        QueueRuntimeStore.markDeferred(ctx, id, "later", backoffMs = 0)
        assertEquals(QueueRuntimeStore.STATE_EXPIRED, state())
    }

    @Test fun noOutcomeWriteRevivesAnExpiredRow() {
        val id = expiredWhileRunning()
        QueueRuntimeStore.markPostponed(ctx, id, "battery", delayMs = 0)
        QueueRuntimeStore.markSucceeded(ctx, id)
        QueueRuntimeStore.markFailed(ctx, id, "x")
        assertEquals(QueueRuntimeStore.STATE_EXPIRED, state())
    }
}
