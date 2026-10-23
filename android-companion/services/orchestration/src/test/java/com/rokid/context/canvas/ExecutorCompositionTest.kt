package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.canvas.helper.InMemoryHelperJobPort
import com.rokid.context.canvas.helper.fenceAt
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** WP8: `py.fn` is in the experiment map only. `Executors.all()` (the live/shadow interpreter's map) is not edited and never holds it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExecutorCompositionTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(app)) }
    @After fun tearDown() { MemoryGraph.get(app).db.close(); MemoryGraph.resetForTests(null) }

    private val fence: suspend () -> com.rokid.context.memory.canvas.HelperFence = { fenceAt(1) }

    @Test fun interpreterMapHasNoPythonExecutor() {
        assertFalse(NodeTypes.PY_FN in Executors.all())
        assertFalse("no PythonExecutor in Executors.kt", "PythonExecutor" in File("src/main/java/com/rokid/context/canvas/Executors.kt").readText())
    }

    @Test fun experimentExtrasHoldExactlyThePythonExecutor() {
        val extras = ExperimentExecutors.extras(InMemoryHelperJobPort(), fence)
        assertEquals(setOf(NodeTypes.PY_FN), extras.keys)
        assertTrue(extras.getValue(NodeTypes.PY_FN) is PythonExecutor)
        assertTrue("the extras do not overlap the built-ins, so the phone's composeExecutors accepts them", extras.keys.none { it in Executors.all() })
    }

    @Test fun runnerMapIsTheBuiltInsPlusPython() {
        val all = ExperimentExecutors.all(InMemoryHelperJobPort(), fence)
        assertEquals(Executors.all().keys + NodeTypes.PY_FN, all.keys)
        for ((t, e) in Executors.all()) assertSame("built-in $t is the same object", e, all.getValue(t))
    }

    @Test fun theProductionBindingBuildsTheSameMap() {
        val p = ExperimentExecutors.forApp(app, InMemoryHelperJobPort())
        assertEquals(setOf(NodeTypes.PY_FN), p.keys)
        assertNotNull(p[NodeTypes.PY_FN])
    }
}
