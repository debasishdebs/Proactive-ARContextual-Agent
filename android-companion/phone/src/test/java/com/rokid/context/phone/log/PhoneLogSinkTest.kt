package com.rokid.context.phone.log

import com.rokid.context.core.log.AsyncLineWriter
import com.rokid.context.core.log.Level
import com.rokid.context.core.log.Line
import com.rokid.context.core.log.LogSink
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.log.Route
import com.rokid.context.memory.forget.ForgetListeners
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PhoneLogSinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val writers = ArrayList<AsyncLineWriter>()
    private lateinit var logFile: File
    private val logcat = ArrayList<Triple<Level, String, String>>()

    @Before fun setUp() { logFile = File(tmp.newFolder("files"), "logs/pca.log") }
    @After fun tearDown() { writers.forEach { it.close(false) }; ForgetListeners.clearForTests() }

    private fun writer(beforeWrite: () -> Unit = {}) = AsyncLineWriter(logFile, maxBytes = 6_000_000L, beforeWrite = beforeWrite).also { writers += it }
    private fun sink(w: AsyncLineWriter = writer()) = PhoneLogSink(w) { l, t, m -> synchronized(logcat) { logcat += Triple(l, t, m) } }
    private fun fileText() = if (logFile.exists()) logFile.readText() else ""

    @Test fun phoneSinkRoutingMatrix() {
        for (logcatOn in listOf(false, true)) for (fileOn in listOf(false, true)) {
            logcat.clear(); logFile.parentFile?.deleteRecursively()
            val w = writer(); val s = sink(w)
            s.emit(Line(Level.W, "TagX", "msg-${logcatOn}-${fileOn}", 1L), Route(logcat = logcatOn, file = fileOn))
            assertTrue(w.awaitIdle(2000))
            assertEquals("logcat for $logcatOn/$fileOn", if (logcatOn) 1 else 0, logcat.size)
            assertEquals("file for $logcatOn/$fileOn", fileOn, fileText().contains("msg-${logcatOn}-${fileOn}"))
        }
    }

    @Test fun phoneLogSinkKeepsLogcatLevelAndTag() {
        val s = sink()
        for (lv in Level.values()) s.emit(Line(lv, "T$lv", "m$lv", 1L), Route(logcat = true, file = false))
        assertEquals(Level.values().map { Triple(it, "T$it", "m$it") }, logcat.toList())
    }

    @Test fun phoneFileRoutedPerPolicyEndToEnd() {
        val w = writer(); val s = sink(w)
        // real policy through PcLog is covered in canvasPortGoesThroughTheGate; here the file is what the Route says
        s.emit(Line(Level.I, "T", "info-line", 1L), Route(logcat = true, file = false))
        s.emit(Line(Level.W, "T", "warn-line", 1L), Route(logcat = true, file = true))
        assertTrue(w.awaitIdle(2000))
        assertFalse(fileText().contains("info-line")); assertTrue(fileText().contains("warn-line"))
    }

    @Test fun fileLivesUnderFilesLogsWithSixMegabyteTwoFileBudget() {
        val root = tmp.newFolder("fx")
        val logging = PhoneLogging.install(root, { MutableStateFlow(false) }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), installer = { true }, once = AtomicBoolean(false))
        assertNotNull(logging)
        logging!!.emit(Line(Level.E, "T", "boom", 1L), Route(logcat = false, file = true))
        val f = File(root, "logs/pca.log")
        val end = System.nanoTime() + 3_000_000_000
        while (!f.exists() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue(f.exists())
        assertEquals("logs", PhoneLogSink.LOG_DIR)
        assertEquals(6_000_000L, PhoneLogSink.MAX_BYTES)
        logging.close()
    }

    @Test fun installOnce() {
        val calls = AtomicInteger(0)
        val once = AtomicBoolean(false)
        val flow = MutableStateFlow(false); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val a = PhoneLogging.install(tmp.newFolder("a"), { flow }, scope, installer = { calls.incrementAndGet(); true }, once = once)
        val b = PhoneLogging.install(tmp.newFolder("b"), { flow }, scope, installer = { calls.incrementAndGet(); true }, once = once)
        assertNotNull(a); assertNull(b)
        assertEquals(1, calls.get())
        a!!.close()
    }

    @Test fun refusedInstallClosesTheWriterAndDoesNotCountAsInstalled() {
        val once = AtomicBoolean(false)
        val flow = MutableStateFlow(false); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        assertNull(PhoneLogging.install(tmp.newFolder("c"), { flow }, scope, installer = { false }, once = once))
        assertNotNull(PhoneLogging.install(tmp.newFolder("d"), { flow }, scope, installer = { true }, once = once)?.also { it.close() })
    }

    @Test fun developerModeSettingDrivesDiagnostic() {
        val flow = MutableStateFlow(true)
        val seen = ArrayList<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val s = PhoneLogging.install(tmp.newFolder("e"), { flow }, scope, installer = { true }, once = AtomicBoolean(false), setDiagnostic = { seen += it })
        assertEquals(listOf(true), seen.toList())      // the persisted value is applied at start
        flow.value = false; flow.value = true
        assertEquals(listOf(true, false, true), seen.toList())
        s!!.close()
    }

    @Test fun wipeClearsBothRotationsAndAwaitsAck() {
        val w = writer(); val s = sink(w)
        s.emit(Line(Level.W, "T", "keep-me-not", 1L), Route(false, true))
        assertTrue(w.awaitIdle(2000)); assertTrue(logFile.exists())
        File(logFile.path + ".1").writeText("old\n")
        assertTrue(s.wipe(5000))
        assertFalse(logFile.exists()); assertFalse(File(logFile.path + ".1").exists())
    }

    @Test fun appendRacingTheClearCannotRecreateTheFile() {
        val inFlight = CountDownLatch(1); val release = CountDownLatch(1)
        val w = writer(beforeWrite = { inFlight.countDown(); release.await(5, TimeUnit.SECONDS) })
        val s = sink(w)
        s.emit(Line(Level.W, "T", "A-inflight", 1L), Route(false, true))
        assertTrue(inFlight.await(2, TimeUnit.SECONDS))
        s.emit(Line(Level.W, "T", "B-queued", 1L), Route(false, true))
        s.emit(Line(Level.W, "T", "C-queued", 1L), Route(false, true))
        val done = AtomicBoolean(false)
        val t = Thread { done.set(s.wipe(5000)) }.apply { start() }
        Thread.sleep(100)
        release.countDown(); t.join(5000)
        assertTrue(done.get())
        assertTrue(w.awaitIdle(2000))
        assertFalse("a queued append recreated the cleared file: ${fileText()}", logFile.exists())
    }

    @Test fun deleteAllListenerWipesTheLog() {
        val w = writer(); val s = sink(w)
        s.emit(Line(Level.W, "T", "x", 1L), Route(false, true)); assertTrue(w.awaitIdle(2000)); assertTrue(logFile.exists())
        ForgetListeners.register(LogWipeListener(s))
        runBlocking { ForgetListeners.deleteAll() }
        assertFalse(logFile.exists())
    }

    @Test fun canvasPortGoesThroughTheGate() {
        val got = java.util.concurrent.CopyOnWriteArrayList<Pair<Line, Route>>()
        val ok = PcLog.install(object : LogSink { override fun emit(line: Line, route: Route) { got += line to route } })
        assumeTrue("PcLog already installed in this JVM", ok)
        PcLog.setDiagnostic(false)
        val port = PhoneLogging.canvasPort()
        port.log(Level.D, "Cv", "dbg"); port.log(Level.W, "Cv", "warn")
        assertEquals(listOf("warn"), got.map { it.first.msg })
        assertEquals(Route(logcat = true, file = true), got.single().second)
    }
}
