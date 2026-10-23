package com.rokid.context.phone.canvas.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.phone.log.PhoneLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * WP6 RED group (logger binding): a graph built the production way logs through the gated core port into the REAL [PhoneLogSink]; every consumer of the call-side logger
 * type (auth, grants, confirm, service, routes, session, adapter) shares that one binding, a helper request logs only the count-only line, and no 3b file touches `android.util.Log`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhoneLogSinkBindingTest {
    @get:Rule val tmp = TemporaryFolder()
    private val lines = java.util.Collections.synchronizedList(mutableListOf<Line>())
    private lateinit var real: PhoneLogSink
    private val shape = Regex("^[a-z]+( [a-z_]+){0,4}( [0-9]+)?$")

    @Before fun install() {
        PcLog.testOnlyResetNeverCallFromProduction()
        real = PhoneLogSink.create(tmp.newFolder("files"))
        PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { lines += line; real.emit(line, route) } })
        PcLog.setDiagnostic(true)
    }
    @After fun reset() { PcLog.testOnlyResetNeverCallFromProduction(); real.close() }

    @Test fun theGraphBuiltWithTheDefaultBindingLogsThroughTheRealSinkWithCountOnlyLines() {
        val h = HelperHarness(ApplicationProvider.getApplicationContext())       // the default `log` argument is PhoneLogging.canvasPort(), as in CanvasGraph.build
        try {
            val token = h.pairHelper()
            h.startRun()
            val (id, gen) = h.confirmAndLease(token)
            h.heartbeat(token, id, gen); h.result(token, id, gen, h.okResult())
            h.backend.session.terminate(SessionEnd.NOTIFICATION_STOP)
            val mine = lines.filter { it.tag == "canvas" }
            assertTrue("3b lines reached the installed real sink: ${lines.size}", mine.size >= 8)
            val secrets = listOf(token, id, "laptop")
            for (l in mine) {
                assertTrue("'${l.msg}' is not a count-only shape", shape.matches(l.msg))
                for (s in secrets) assertFalse("'${l.msg}' carries a secret", l.msg.contains(s))
            }
            assertTrue(real.awaitIdle(5_000))
        } finally { h.close() }
    }

    @Test fun everyCallSideLoggerConsumerOfThe3bPhoneCodeUsesThePortNotAnAndroidLog() {
        val root = File("src/main/java/com/rokid/context/phone/canvas")
        val files = root.walkTopDown().filter { it.extension == "kt" && ("/helper/" in it.path || it.name in setOf("HelperAuth.kt", "CanvasGraph.kt", "CanvasService.kt")) }.toList()
        assertTrue(files.size >= 8)
        val raw = files.filter { Regex("""android\.util\.Log|\bLog\.[diwev]\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("no raw android.util.Log in the 3b phone files", emptyList<String>(), raw)
        val graph = File(root, "CanvasGraph.kt").readText()
        assertEquals("one port in the graph build", 1, Regex("""PhoneLogging\.canvasPort\(\)""").findAll(graph).count())
        assertTrue("the helper backend gets that port", Regex("""HelperBackend\.forApp\(.*?log = port""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(graph))
    }
}
