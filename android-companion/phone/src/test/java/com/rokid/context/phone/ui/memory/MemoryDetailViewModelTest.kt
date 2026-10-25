package com.rokid.context.phone.ui.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MemoryDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<MemoryDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(port: MemoryForget) = MemoryDetailViewModel("m1", graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }, forget = port).also { vms += it }

    @Test fun doubleTapForgetRunsOnce() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1, frameId = "f1"))
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean { calls.incrementAndGet(); gate.await(); return true }
            override suspend fun forgetMoment(frameId: String): Boolean { calls.incrementAndGet(); return true }
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT)
        assertEquals(ForgetKind.FACT, model.state.value.confirm)
        model.confirmForget(); model.confirmForget()
        withTimeout(5_000) { model.state.first { it.forgetting } }
        model.confirmForget()
        gate.complete(Unit)
        withTimeout(5_000) { model.state.first { it.forgotten } }
        assertEquals(1, calls.get())
    }

    @Test fun failureShowsFixedCopy() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(object : MemoryForget {
            override suspend fun forgetFact(memoryId: String): Boolean = throw java.io.IOException("disk /data/x")
            override suspend fun forgetMoment(frameId: String): Boolean = true
        })
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.FACT); model.confirmForget()
        val s = withTimeout(5_000) { model.state.first { it.message != null } }
        assertEquals("Couldn't forget that \u2014 try again", s.message)
        assertTrue(!s.forgetting && !s.forgotten)
    }

    @Test fun momentNeedsAFrame() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val model = vm(MemoryForget.None)
        withTimeout(5_000) { model.state.first { it.found } }
        model.askForget(ForgetKind.MOMENT)
        assertEquals(null, model.state.value.confirm)
    }

    /** GAP-1: an edit leaves the old text in the cached export; the hook (which deletes the cached exports) runs once per saved edit only. */
    @Test fun aSavedEditRunsTheEditHookAndARefusedOneDoesNot() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        val edited = AtomicInteger()
        val model = MemoryDetailViewModel("m1", graph.timeline, graph.memoryEdits, zone = { Fixtures.ZONE }, onTextEdited = { edited.incrementAndGet() }).also { vms += it }
        withTimeout(5_000) { model.state.first { it.found } }
        model.startEdit(); model.updateDraft("   "); model.saveEdit()
        assertEquals(0, edited.get())
        model.updateDraft("Keys in the drawer"); model.saveEdit()
        withTimeout(5_000) { model.state.first { it.message == "Saved" } }
        assertEquals(1, edited.get())
    }
}
