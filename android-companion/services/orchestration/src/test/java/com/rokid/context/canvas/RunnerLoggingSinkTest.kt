package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.PipelineNode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/** WP8 (core plan section 12): `CanvasRunner.kt` logs only through the one logger sink, count-only; no `android.util.Log` is left in it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RunnerLoggingSinkTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness

    @Before fun setUp() { h = PyFnRunHarness(app); ShadowLog.reset() }
    @After fun tearDown() { h.close() }

    @Test fun noAndroidUtilLogIsLeftInCanvasRunner() {
        val text = File("src/main/java/com/rokid/context/canvas/CanvasRunner.kt").readText()
        assertFalse("android.util.Log import", "android.util.Log" in text)
        assertFalse("a direct Log call", Regex("""(?<![A-Za-z.])Log\.[viwed]\(""").containsMatchIn(text))
    }

    @Test fun aFailingExecutorIsLoggedAsOneCountOnlyLineThroughTheSink() = runBlocking<Unit> {
        val boom = object : NodeExecutor {
            override val type = com.rokid.context.ai.canvas.NodeTypes.PY_FN
            override fun nominalCalls(node: PipelineNode, items: Int) = 0
            override fun maxCalls(node: PipelineNode, items: Int) = 0
            override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult> = throw IllegalStateException("secret-token-xyz frame f1")
        }
        val run = h.run(h.runner(py = boom), h.pipeline(), h.frames(2))
        assertEquals("FAILED", run.status)
        assertEquals(listOf("run failed"), h.log.toList())
        val all = h.log.joinToString(" ")
        for (leak in listOf("secret", "token", "IllegalState", "run1", "f1", "f2", "fn")) assertFalse("'$leak' in a log line", leak in all)
        assertTrue("nothing went to android.util.Log", ShadowLog.getLogs().none { it.tag == "CanvasRunner" })
    }
}
