package com.rokid.context.connectors.actions.executors

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingExecutorTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val exec = ShoppingExecutor(db.shoppingDao()) { 5 }

    @After fun close() = db.close()

    @Test fun retryAfterCrashAddsNoDuplicates() = runBlocking {
        val p = shopping("p1", "milk", "eggs")
        assertEquals(ExecResult.Done("shopping:2"), exec.execute(p))
        assertEquals(ExecResult.Done("shopping:2"), exec.execute(p))
        val items = db.shoppingDao().observeAll().first()
        assertEquals(listOf("p1#0", "p1#1"), items.map { it.id }.sorted())
        assertTrue(items.all { it.proposalId == "p1" && !it.done })
    }

    @Test fun undoDeletesOnlyThisProposalsItems() = runBlocking {
        exec.execute(shopping("p1", "milk"))
        exec.execute(shopping("p2", "bread"))
        assertTrue(exec.undo(shopping("p1", "milk")))
        assertEquals(listOf("bread"), db.shoppingDao().observeAll().first().map { it.name })
    }
}
