package com.rokid.context.connectors.actions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Verbosity
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.ProposalState
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ProposalEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Integration carry 6: recover() runs once per process, on the first lazy get() or at install, whichever is first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecoverOnceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val repo = ProposalRepository(db.proposalDao(), db.actionDao(), db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun close() { scope.cancel(); db.close() }

    @Test fun firstCallRecoversAndLaterCallsDoNothing() = runBlocking {
        val exec = CountingExecutor()
        repo.addIgnore(ProposalEntity("p1", "ASK", "a1", ActionType.SHOPPING_ADD.name, "{}", ProposalState.EXECUTING.name, 0, "auto", null, 0))
        val engine = ActionEngine(
            repo, ActionSettingsStore(ctx.getSharedPreferences("t_once", Context.MODE_PRIVATE)), ActionType.entries.associateWith { exec },
            { Verbosity.MEDIUM }, scope, Dispatchers.IO, System::currentTimeMillis,
        )
        val once = RecoverOnce(scope)
        val built = intArrayOf(0)
        repeat(3) { once.run { built[0]++; engine } } // first get(), then install(), then another get()
        withTimeout(5_000) { while (repo.byId("p1")!!.state != ProposalState.EXECUTED.name) kotlinx.coroutines.delay(10) }
        Thread.sleep(200)
        assertEquals(1, exec.executed.get())
        assertEquals(1, built[0])
    }

    @Test fun getAndInstallBothGoThroughTheOnceGuard() {
        val src = File("src/main/kotlin/com/rokid/context/connectors/actions/ActionsGraph.kt").readText()
        val get = src.substringAfter("fun get(context: Context)").substringBefore("fun install(")
        val install = src.substringAfter("fun install(").substringBefore("internal fun executors(")
        assertTrue("get() must recover once", get.contains("recoverOnce.run"))
        assertTrue("install() must recover once", install.contains("recoverOnce.run"))
        assertTrue("install() no longer launches recover itself", !install.contains(".recover()"))
    }
}
