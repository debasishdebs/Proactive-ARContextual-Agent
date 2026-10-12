package com.rokid.context.data.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryRecordStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    @org.junit.Before fun setUp() = com.rokid.context.memory.MemoryGraph.resetForTests(com.rokid.context.memory.db.DatabaseFactory.inMemory(ctx))
    @org.junit.After fun tearDown() = com.rokid.context.memory.MemoryGraph.resetForTests(null)

    @Test fun recentIsNewestFirstAndSanitisesContent() {
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_1", "short_term", "a\tb\nc", 0.9, 1))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_2", "short_term", "second", 0.5, 2))
        val r = MemoryRecordStore.recent(ctx, 10)
        assertEquals(listOf("rokid_mem_2", "rokid_mem_1"), r.map { it.id })
        assertEquals("a b c", r[1].content)
    }

    @Test fun purgeNonRokidKeepsOnlyRokidIds() {
        MemoryRecordStore.append(ctx, MemoryRecord("other_1", "short_term", "x", 0.1, 1))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_3", "short_term", "y", 0.1, 2))
        MemoryRecordStore.purgeNonRokid(ctx)
        assertEquals(listOf("rokid_mem_3"), MemoryRecordStore.recent(ctx, 10).map { it.id })
    }

    @Test fun purgeLowSignalDropsReviewsAndMarkers() {
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_review_1", "review", "needs review", 0.1, 1))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_4", "short_term", "Metadata only frame", 0.1, 2))
        MemoryRecordStore.append(ctx, MemoryRecord("rokid_mem_5", "short_term", "Milk carton nearly empty", 0.8, 3))
        MemoryRecordStore.purgeLowSignalSummaries(ctx)
        assertEquals(listOf("rokid_mem_5"), MemoryRecordStore.recent(ctx, 10).map { it.id })
    }
}
