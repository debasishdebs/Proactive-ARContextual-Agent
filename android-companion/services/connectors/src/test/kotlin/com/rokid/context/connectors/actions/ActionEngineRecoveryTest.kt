package com.rokid.context.connectors.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.connectors.actions.executors.ShoppingExecutor
import com.rokid.context.connectors.actions.executors.shopping
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ActionEngineRecoveryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun close() { scope.cancel(); db.close() }

    @Test fun executingRowReExecutedOnRecover() = runBlocking {
        val exec = ShoppingExecutor(db.shoppingDao()) { 5 }
        val row = shopping("p1", "milk", "eggs")
        repo.create(row, 1)
        exec.execute(row) // the effect landed, then the process died before EXECUTED
        val engine = ActionEngine(
            repo, ActionSettingsStore(ctx.getSharedPreferences("t_rec", Context.MODE_PRIVATE)), mapOf(ActionType.SHOPPING_ADD to exec),
            { Verbosity.MEDIUM }, scope, Dispatchers.IO, { 10L },
        )
        engine.recover()
        assertEquals(ProposalState.EXECUTED.name, repo.byId("p1")!!.state)
        assertEquals("shopping:2", repo.byId("p1")!!.createdRef)
        assertEquals(2, db.shoppingDao().observeAll().first().size)
    }
}
