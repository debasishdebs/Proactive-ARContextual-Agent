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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private class MemPending(@Volatile var value: Boolean = false) : WipePendingStore {
    val history = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
    @Volatile var failWrites = 0                 // the next n writes report "not persisted"
    @Volatile var alwaysFail = false
    @Volatile var readFails = false
    @Volatile var pauseFalseWrite: Pair<CountDownLatch, CountDownLatch>? = null     // (entered, resume) for the first write(false)
    override fun read(): Boolean? = if (readFails) null else value
    override fun write(pending: Boolean): Boolean {
        if (alwaysFail) return false
        if (failWrites > 0) { failWrites--; return false }
        if (!pending) pauseFalseWrite?.let { (entered, resume) -> pauseFalseWrite = null; entered.countDown(); resume.await(5, TimeUnit.SECONDS) }
        value = pending; history += pending; return true
    }
}

class PhoneLogSinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private val writers = ArrayList<AsyncLineWriter>()
    private lateinit var logFile: File
    private val logcat = ArrayList<Triple<Level, String, String>>()
    private val unconfined get() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() { logFile = File(tmp.newFolder("files"), "logs/pca.log"); PcLog.testOnlyResetNeverCallFromProduction() }
    @After fun tearDown() { writers.forEach { it.close(false) }; ForgetListeners.clearForTests(); PcLog.testOnlyResetNeverCallFromProduction() }

    private fun writer(maxBytes: Long = 6_000_000L, beforeWrite: () -> Unit = {}) =
        AsyncLineWriter(logFile, maxBytes = maxBytes, beforeWrite = beforeWrite).also { writers += it }
    private fun sink(w: AsyncLineWriter = writer()) = PhoneLogSink(w) { l, t, m -> synchronized(logcat) { logcat += Triple(l, t, m) } }
    private fun fileText() = if (logFile.exists()) logFile.readText() else ""
    private fun awaitTrue(ms: Long = 3000, c: () -> Boolean): Boolean {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) { if (c()) return true; Thread.yield() }
        return c()
    }

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
        s.emit(Line(Level.I, "T", "info-line", 1L), Route(logcat = true, file = false))
        s.emit(Line(Level.W, "T", "warn-line", 1L), Route(logcat = true, file = true))
        assertTrue(w.awaitIdle(2000))
        assertFalse(fileText().contains("info-line")); assertTrue(fileText().contains("warn-line"))
    }

    @Test fun fileLivesUnderFilesLogsAndDefaultBudgetIsTwoThreeMegabyteFiles() {
        assertEquals("logs", PhoneLogSink.LOG_DIR)
        assertEquals(6_000_000L, PhoneLogSink.MAX_BYTES)
        val root = tmp.newFolder("fx")
        val s = PhoneLogSink.create(root)
        s.emit(Line(Level.E, "T", "boom", 1L), Route(logcat = false, file = true))
        assertTrue(awaitTrue { File(root, "logs/pca.log").exists() })
        s.close()
    }

    @Test fun realRotationKeepsTwoFilesWithinBudget() {
        val root = tmp.newFolder("rot"); val budget = 4_000L
        val s = PhoneLogSink.create(root, maxBytes = budget)
        val f = File(root, "logs/pca.log"); val old = File(root, "logs/pca.log.1")
        for (i in 0 until 200) s.emit(Line(Level.W, "T", "line-$i-" + "x".repeat(80), 1L), Route(false, true))
        s.emit(Line(Level.W, "T", "final-record", 1L), Route(false, true))
        assertTrue("idle: everything written", s.awaitIdle(5000))      // before close(false), which discards what is queued
        assertTrue(old.exists()); assertTrue(f.length() <= budget / 2); assertTrue(old.length() <= budget / 2)
        assertTrue("the last record survives the rotation", f.readText().contains("final-record"))
        assertTrue("never a third file", setOf("pca.log", "pca.log.1").containsAll(root.resolve("logs").list()!!.toSet()))
        val done = s.close()
        done.get(3, TimeUnit.SECONDS)       // termination observed separately from idleness
        assertTrue(done.isDone)
    }

    @Test fun installCreatesNoDirectoryAndTouchesNoFileOnTheCallingThread() {
        val calls = ArrayList<String>()
        val caller = Thread.currentThread()
        val dir = object : File(tmp.root, "never-created") {
            private fun note(n: String) { if (Thread.currentThread() === caller) calls += n }
            override fun exists(): Boolean { note("exists"); return super.exists() }
            override fun mkdirs(): Boolean { note("mkdirs"); return super.mkdirs() }
            override fun mkdir(): Boolean { note("mkdir"); return super.mkdir() }
            override fun isDirectory(): Boolean { note("isDirectory"); return super.isDirectory() }
            override fun length(): Long { note("length"); return super.length() }
            override fun list(): Array<String>? { note("list"); return super.list() }
        }
        val i = PhoneLogging.install(dir, { MutableStateFlow(false) }, MemPending(), unconfined, installer = { true }, once = AtomicBoolean(false))
        assertNotNull(i)
        assertEquals(emptyList<String>(), calls)
        assertFalse("directory must be created by the writer, not by install", dir.exists())
        i!!.sink.close()
    }

    @Test fun installOnce() {
        val calls = AtomicInteger(0); val once = AtomicBoolean(false)
        val a = PhoneLogging.install(tmp.newFolder("a"), { MutableStateFlow(false) }, MemPending(), unconfined, installer = { calls.incrementAndGet(); true }, once = once)
        val b = PhoneLogging.install(tmp.newFolder("b"), { MutableStateFlow(false) }, MemPending(), unconfined, installer = { calls.incrementAndGet(); true }, once = once)
        assertNotNull(a); assertNull(b)
        assertEquals(1, calls.get())
        a!!.sink.close()
    }

    @Test fun refusedInstallTerminatesTheWriterAndRearms() {
        val once = AtomicBoolean(false)
        val w = writer(); val s = sink(w)
        val i = PhoneLogging.install(tmp.newFolder("c"), { MutableStateFlow(false) }, MemPending(), unconfined, installer = { false }, once = once, sinkFactory = { s })
        assertNotNull(i); runBlocking { i!!.ready.join() }
        assertEquals(InstallResult.REFUSED, i!!.result)
        assertFalse("re-armed after a refused install", once.get())
        s.close().get(3, TimeUnit.SECONDS)       // termination observed (the install path already closed it)
        s.emit(Line(Level.W, "T", "after-refusal", 1L), Route(false, true))      // a closed writer accepts nothing
        assertTrue(w.awaitIdle(2000))
        assertFalse(fileText().contains("after-refusal"))
        assertNotNull(PhoneLogging.install(tmp.newFolder("d"), { MutableStateFlow(false) }, MemPending(), unconfined, installer = { true }, once = once)?.also { it.sink.close() })
    }

    @Test fun developerModeSettingDrivesDiagnostic() {
        val flow = MutableStateFlow(true); val seen = ArrayList<Boolean>()
        val i = PhoneLogging.install(tmp.newFolder("e"), { flow }, MemPending(), unconfined, installer = { true }, once = AtomicBoolean(false), setDiagnostic = { seen += it })
        runBlocking { i!!.ready.join() }
        assertEquals("pre-install snapshot, then the collector's initial value", listOf(true, true), seen.toList())
        flow.value = false; flow.value = true
        assertEquals(listOf(true, true, false, true), seen.toList())
        i!!.sink.close()
    }

    @Test fun persistedFlagIsAppliedBeforeTheBootstrapRingDrains() {
        val order = ArrayList<String>()
        val i = PhoneLogging.install(tmp.newFolder("o"), { MutableStateFlow(true) }, MemPending(), unconfined,
            installer = { order += "install"; true }, once = AtomicBoolean(false), setDiagnostic = { order += "diag=$it" })
        runBlocking { i!!.ready.join() }
        assertEquals(listOf("diag=true", "install", "diag=true"), order.toList())
        i!!.sink.close()
    }

    @Test fun delayedLoaderKeepsEarlyDebugLinesWhenPersistedFlagIsTrue() {
        val root = tmp.newFolder("early"); val release = CountDownLatch(1)
        val i = PhoneLogging.install(root, { release.await(5, TimeUnit.SECONDS); MutableStateFlow(true) }, MemPending(),
            CoroutineScope(SupervisorJob() + Dispatchers.IO), once = AtomicBoolean(false), sinkFactory = { PhoneLogSink(writerAt(File(it, "logs/pca.log")), { _, _, _ -> }) })
        PcLog.d("Early", "early-debug-line"); PcLog.i("Early", "early-info-line")      // before the loader finished: bootstrap ring
        release.countDown()
        runBlocking { i!!.ready.join() }
        assertTrue(writers.last().awaitIdle(3000))
        val text = File(root, "logs/pca.log").readText()
        assertTrue("early D kept when the persisted flag is true: $text", text.contains("early-debug-line"))
        assertTrue(text.contains("early-info-line"))
    }

    private fun writerAt(f: File) = AsyncLineWriter(f, maxBytes = 6_000_000L).also { writers += it }

    @Test fun diagnosticAndLevelMatrixThroughPcLogAndPhoneSink() {
        for (diag in listOf(false, true)) {
            PcLog.testOnlyResetNeverCallFromProduction(); logcat.clear(); logFile.parentFile?.deleteRecursively()
            val w = writer(); val s = sink(w)
            val i = PhoneLogging.install(tmp.newFolder("m$diag"), { MutableStateFlow(diag) }, MemPending(), unconfined, once = AtomicBoolean(false), sinkFactory = { s })
            assertNotNull(i); runBlocking { i!!.ready.join() }
            val port = PhoneLogging.canvasPort()
            for (lv in Level.values()) { PcLog.log(lv, "D-$lv", "direct-$lv"); port.log(lv, "P-$lv", "port-$lv") }
            assertTrue(w.awaitIdle(2000))
            for (lv in Level.values()) for (kind in listOf("direct", "port")) {
                val msg = "$kind-$lv"
                val expLogcat = diag || lv >= Level.I
                val expFile = diag || lv >= Level.W
                assertEquals("logcat $msg diag=$diag", expLogcat, logcat.any { it.third == msg })
                assertEquals("file $msg diag=$diag", expFile, fileText().contains(msg))
            }
        }
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
        assertEquals(2, w.queueSize())
        val done = AtomicBoolean(false)
        val t = Thread { done.set(s.wipe(5000)) }.apply { start() }
        assertTrue("clear command enqueued (queued lines discarded)", awaitTrue { w.queueSize() == 0 })
        release.countDown(); t.join(5000)
        assertTrue(done.get())
        assertTrue(w.awaitIdle(2000))
        assertFalse("a queued append recreated the cleared file: ${fileText()}", logFile.exists())
    }

    @Test fun deleteAllListenerWipesTheLogAndClearsPending() {
        val w = writer(); val s = sink(w); val pend = MemPending()
        s.emit(Line(Level.W, "T", "x", 1L), Route(false, true)); assertTrue(w.awaitIdle(2000)); assertTrue(logFile.exists())
        ForgetListeners.register(LogWipeListener(LogWiper(s, pend)))
        runBlocking { ForgetListeners.deleteAll() }
        assertFalse(logFile.exists())
        assertEquals("pending set before the wipe, cleared after the ack", listOf(true, false), pend.history)
    }

    @Test fun wipeTimeoutKeepsPendingAndNextStartRetries() {
        val dir = tmp.newFolder("t"); val f = File(dir, "logs/pca.log")
        val inFlight = CountDownLatch(1); val release = CountDownLatch(1)
        val stalled = AsyncLineWriter(f, maxBytes = 6_000_000L, beforeWrite = { inFlight.countDown(); release.await(10, TimeUnit.SECONDS) }).also { writers += it }
        val s1 = PhoneLogSink(stalled) { _, _, _ -> }
        s1.emit(Line(Level.W, "T", "old-line", 1L), Route(false, true))
        assertTrue(inFlight.await(2, TimeUnit.SECONDS))
        val pend = MemPending()
        assertFalse("timeout", LogWiper(s1, pend, timeoutMillis = 100).wipe())
        assertTrue("failure/timeout stays pending", pend.value)
        release.countDown(); assertTrue(stalled.awaitIdle(3000))
        // "process death" + next start: a fresh sink on the same directory through the same install path
        f.parentFile!!.mkdirs(); f.writeText("survivor\n")
        val i = PhoneLogging.install(dir, { MutableStateFlow(false) }, pend, unconfined, installer = { true }, once = AtomicBoolean(false))
        runBlocking { i!!.ready.join() }
        assertFalse("pending wipe retried at start", f.exists())
        assertFalse(pend.value)
        i!!.sink.close()
    }

    @Test fun noPendingNoWipeAtStart() {
        val dir = tmp.newFolder("n"); val f = File(dir, "logs/pca.log"); f.parentFile!!.mkdirs(); f.writeText("keep\n")
        val i = PhoneLogging.install(dir, { MutableStateFlow(false) }, MemPending(false), unconfined, installer = { true }, once = AtomicBoolean(false))
        runBlocking { i!!.ready.join() }
        assertTrue(f.exists())
        i!!.sink.close()
    }

    @Test fun deleteAllHookOwnership() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        assertTrue(File(root, "phone/src/main/java/com/rokid/context/ContextApp.kt").readText().contains("LogWipeListener(it.wiper, it.ready)"))
        assertTrue(File(root, "services/orchestration/src/main/java/com/rokid/context/orchestration/MemoryWipe.kt").readText().contains("ForgetListeners.deleteAll()"))
    }

    @Test fun failedPersistIsRetriedBoundedAndNotTakenAsSuccess() {
        val pend = MemPending(); pend.failWrites = 2
        assertFalse(LogWiper({ false }, pend).wipe())
        assertTrue("third attempt persisted the request", pend.value)
        val always = MemPending().also { it.alwaysFail = true }
        val ops = AtomicInteger(0); val ok = AtomicBoolean(false)
        val w = LogWiper({ ops.incrementAndGet(); ok.get() }, always)
        assertFalse(w.wipe()); assertEquals(1, ops.get())
        ok.set(true); w.retryPending()
        assertEquals("kept in memory and retried in this process", 2, ops.get())
        w.retryPending(); assertEquals("done: no third wipe", 2, ops.get())
    }

    @Test fun unreadablePendingStateIsNotTakenAsNotPending() {
        val pend = MemPending(false).also { it.readFails = true }
        val ops = AtomicInteger(0)
        LogWiper({ ops.incrementAndGet(); true }, pend).retryPending()
        assertEquals(1, ops.get())
        val clean = AtomicInteger(0)
        LogWiper({ clean.incrementAndGet(); true }, MemPending(false)).retryPending()
        assertEquals(0, clean.get())
    }

    @Test fun overlappingWipesCannotClearEachOthersRequest() {
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val pend = MemPending().also { it.pauseFalseWrite = entered to resume }
        val ops = AtomicInteger(0)
        val w = LogWiper({ ops.incrementAndGet() == 1 }, pend)      // A acknowledged, B times out
        val a = Thread { w.wipe() }.apply { start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))              // A: acknowledged, paused before clearing its marker
        val b = Thread { w.wipe() }.apply { start() }
        assertTrue(awaitTrue { b.state == Thread.State.BLOCKED || ops.get() >= 2 })
        resume.countDown(); a.join(5000); b.join(5000)
        assertTrue("B's request must survive A's late clear", pend.value)
    }

    @Test fun listenerWaitsForTheInstallBarrierBeforeClearing() {
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ops = AtomicInteger(0)
        val l = LogWipeListener(LogWiper({ ops.incrementAndGet(); true }, MemPending()), ready)
        val t = Thread { runBlocking { l.onDeleteAll() } }.apply { start() }
        Thread.sleep(200)       // negative check: can only false-pass, never flake
        assertEquals(0, ops.get())
        ready.complete(Unit); t.join(5000)
        assertEquals(1, ops.get())
    }

    @Test fun acknowledgedWipeIsNotUndoneByTheBootstrapReplay() {
        val root = tmp.newFolder("barrier"); val release = CountDownLatch(1); val pend = MemPending()
        val i = PhoneLogging.install(root, { release.await(5, TimeUnit.SECONDS); MutableStateFlow(false) }, pend,
            CoroutineScope(SupervisorJob() + Dispatchers.IO), once = AtomicBoolean(false), sinkFactory = { PhoneLogSink(writerAt(File(it, "logs/pca.log")), { _, _, _ -> }) })
        PcLog.w("Early", "old-record")                       // bootstrap ring
        val t = Thread { runBlocking { LogWipeListener(i!!.wiper, i.ready).onDeleteAll() } }.apply { start() }
        awaitTrue(400) { pend.history.isNotEmpty() }         // with the barrier nothing happens until the loader is released
        release.countDown()
        t.join(8000); runBlocking { i!!.ready.join() }
        assertTrue(writers.last().awaitIdle(3000))
        assertFalse("old record reappeared after the acknowledged wipe: ${fileText(File(root, "logs/pca.log"))}", File(root, "logs/pca.log").exists())
        assertFalse(pend.value)
    }

    private fun fileText(f: File) = if (f.exists()) f.readText() else ""

    @Test fun scopeCancelledBeforeStartCleansUpAndRearms() {
        val once = AtomicBoolean(false)
        val w = writer(); val s = sink(w)
        val i = PhoneLogging.install(tmp.newFolder("cx"), { MutableStateFlow(false) }, MemPending(), CoroutineScope(kotlinx.coroutines.Job().also { it.cancel() }),
            installer = { error("must not run") }, once = once, sinkFactory = { s })
        runBlocking { i!!.ready.join() }
        assertEquals(InstallResult.CANCELLED, i!!.result)
        assertFalse(once.get())
        s.emit(Line(Level.W, "T", "late", 1L), Route(false, true)); assertTrue(w.awaitIdle(2000))
        assertFalse("the install path closed the writer", logFile.exists())
        s.close().get(3, TimeUnit.SECONDS)
    }

    @Test fun scopeCancelledDuringLoaderNeverInstalls() {
        val release = CountDownLatch(1); val once = AtomicBoolean(false); val installs = AtomicInteger(0)
        val job = kotlinx.coroutines.Job()
        val i = PhoneLogging.install(tmp.newFolder("cy"), { release.await(5, TimeUnit.SECONDS); MutableStateFlow(false) }, MemPending(),
            CoroutineScope(job + Dispatchers.IO), installer = { installs.incrementAndGet(); true }, once = once)
        job.cancel(); release.countDown()
        runBlocking { i!!.ready.join() }
        assertEquals(0, installs.get()); assertEquals(InstallResult.CANCELLED, i!!.result); assertFalse(once.get())
    }

    @Test fun successfulInstallReportsSuccess() {
        val i = PhoneLogging.install(tmp.newFolder("ok"), { MutableStateFlow(false) }, MemPending(), unconfined, installer = { true }, once = AtomicBoolean(false))
        runBlocking { i!!.ready.join() }
        assertEquals(InstallResult.SUCCESS, i!!.result); i.sink.close()
    }

    @Test fun noMainSourceCallsTheTestOnlyReset() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val hits = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.extension == "kt" && "/src/main/" in it.path && it.name != "PcLog.kt" && "testOnlyResetNeverCallFromProduction" in it.readText() }.toList()
        assertTrue("production code calls the test-only reset: $hits", hits.isEmpty())
    }
}
