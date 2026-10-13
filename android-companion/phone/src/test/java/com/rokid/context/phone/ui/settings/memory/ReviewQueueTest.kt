package com.rokid.context.phone.ui.settings.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReviewQueueTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test fun keepingAFactMovesItOutOfTheQueueIntoTheFeeds() = runBlocking {
        val g = TestGraph.fresh(ctx)
        g.memories.add(Fixtures.memory("r1", "Maybe a cat", Fixtures.NOW, confidence = 0.3, review = ReviewState.NEEDS_REVIEW))
        g.memories.add(Fixtures.memory("ok", "Keys", Fixtures.NOW))
        val vm = ReviewQueueViewModel(g.memoryEdits, zone = { Fixtures.ZONE })
        assertEquals(listOf("r1"), withTimeout(5_000) { vm.state.first { it.items.isNotEmpty() } }.items.map { it.id })
        vm.keep("r1")
        withTimeout(5_000) { vm.state.first { it.items.isEmpty() } }
        assertEquals(ReviewState.OK, g.db.memoryViewDao().byId("r1")!!.reviewState)
        assertFalse(g.memoryEdits.approve("ok")) // only review-queue facts can be approved
        g.memories.add(Fixtures.memory("r2", "Maybe a dog", Fixtures.NOW, review = ReviewState.NEEDS_REVIEW))
        assertEquals(1, withTimeout(5_000) { g.memoryEdits.needsReviewCount().first { it == 1 } }) // review nit: a COUNT, no cap
        vm.cancelScope(); TestGraph.close(ctx)
    }
}
