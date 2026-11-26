package com.rokid.context.vision.local

import com.rokid.context.core.model.vision.CaptionRefusal
import com.rokid.context.core.model.vision.CaptionRefused
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class SmolVlmCaptionerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeEngine(val loadError: String? = null, val failRun: Boolean = false, val text: String = "  A fort with stone walls.  ", val runMs: Long = 0) : CaptionEngine {
        val loads = AtomicInteger(); val runs = AtomicInteger(); val releases = AtomicInteger()
        private val running = AtomicInteger(); val maxConcurrent = AtomicInteger()
        var lastPrompt: String? = null; var lastMaxTokens = 0; var lastThreads = 0
        override fun load(files: CaptionModelFiles, threads: Int): String? { loads.incrementAndGet(); lastThreads = threads; return loadError }
        override fun run(frame: RgbFrame, prompt: String, maxTokens: Int): String {
            val now = running.incrementAndGet(); maxConcurrent.updateAndGet { maxOf(it, now) }
            try { if (runMs > 0) Thread.sleep(runMs); lastPrompt = prompt; lastMaxTokens = maxTokens; runs.incrementAndGet(); if (failRun) error("native boom"); return text } finally { running.decrementAndGet() }
        }
        override fun release() { releases.incrementAndGet() }
    }

    private val ok = DeviceState(thermal = 0, batterySaver = false, batteryPct = 80, charging = false)
    private val frame = RgbFrame(ByteArray(12), 2, 2)

    private fun files() = CaptionModelFiles(tmp.newFile(), tmp.newFile())

    private fun captioner(
        engine: FakeEngine, enabled: Boolean = true, device: () -> DeviceState = { ok }, models: (() -> CaptionModelFiles?)? = null,
        loader: FrameLoader = FrameLoader { _, _ -> frame }, dispatcher: kotlinx.coroutines.CoroutineDispatcher, main: Boolean = false, idleMs: Long = 5_000,
    ): SmolVlmCaptioner { val f = files(); return SmolVlmCaptioner(engine, models ?: { f }, { enabled }, device, loader, dispatcher, { main }, idleReleaseMs = idleMs) }

    private fun reason(r: Result<String>) = (r.exceptionOrNull() as CaptionRefused).reason

    @Test fun disabledByDefaultRefusesAndNeverLoads() = runTest {
        val e = FakeEngine()
        val c = captioner(e, enabled = false, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals(CaptionRefusal.DISABLED, reason(c.caption("/x.jpg")))
        assertEquals(0, e.loads.get()); assertEquals(0, e.runs.get())
    }

    @Test fun capturesTheCaptionWithTheOwnerPromptTokensAndThreads() = runTest {
        val e = FakeEngine()
        val c = captioner(e, dispatcher = StandardTestDispatcher(testScheduler))
        assertEquals("A fort with stone walls.", c.caption("/x.jpg").getOrThrow())
        assertEquals("Describe where this photo was taken: the kind of place (home, museum gallery, fort, show, restaurant), the main objects, and the decor, in two short sentences.", e.lastPrompt)
        assertEquals(48, e.lastMaxTokens); assertEquals(6, e.lastThreads); assertEquals(640, CaptionConfig.MAX_SIDE_PX)
    }

    @Test fun refusesOnSevereThermalBatterySaverAndLowBatteryWithoutLoading() = runTest {
        val cases = listOf(
            ok.copy(thermal = 3) to CaptionRefusal.THERMAL, ok.copy(thermal = 5) to CaptionRefusal.THERMAL,
            ok.copy(batterySaver = true) to CaptionRefusal.BATTERY_SAVER,
            ok.copy(batteryPct = 19) to CaptionRefusal.BATTERY_LOW,
        )
        for ((state, why) in cases) {
            val e = FakeEngine()
            val c = captioner(e, device = { state }, dispatcher = StandardTestDispatcher(testScheduler))
            assertEquals(why, reason(c.caption("/x.jpg")))
            assertEquals(0, e.loads.get()); assertEquals(0, e.runs.get())
        }
    }

    @Test fun lowBatteryIsFineWhileChargingAndModerateHeatIsFine() = runTest {
        val e = FakeEngine()
        val c = captioner(e, device = { ok.copy(batteryPct = 5, charging = true, thermal = 2) }, dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(c.caption("/x.jpg").isSuccess)
        assertNull(CaptionPolicy.refusal(ok.copy(batteryPct = 20)))
        assertEquals(CaptionRefusal.BATTERY_LOW, CaptionPolicy.refusal(ok.copy(batteryPct = 19)))
    }

    @Test fun thermalIsCheckedAgainOnEveryCaption() = runTest {
        var s = ok
        val e = FakeEngine()
        val c = captioner(e, device = { s }, dispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(c.caption("/x.jpg").isSuccess)
        s = ok.copy(thermal = 4)
        assertEquals(CaptionRefusal.THERMAL, reason(c.caption("/x.jpg")))
        assertEquals(1, e.runs.get())
    }

    @Test fun modelIsReleasedAfterIdleAndReloadedOnNextUse() = runTest {
        val e = FakeEngine()
        val c = captioner(e, dispatcher = StandardTestDispatcher(testScheduler))
        val job = async { c.caption("/a.jpg") }
        runCurrent(); job.await()
        assertTrue(c.isLoaded()); assertEquals(0, e.releases.get())
        advanceTimeBy(4_999); runCurrent()
        assertTrue("still loaded just before 5 s", c.isLoaded())
        advanceTimeBy(2); runCurrent()
        assertFalse(c.isLoaded()); assertEquals(1, e.releases.get())
        val second = async { c.caption("/b.jpg") }
        runCurrent(); second.await()
        assertEquals(2, e.loads.get())
    }

    @Test fun aNewCaptionBeforeTheIdleTimerKeepsTheModelLoaded() = runTest {
        val e = FakeEngine()
        val c = captioner(e, dispatcher = StandardTestDispatcher(testScheduler))
        val first = async { c.caption("/a.jpg") }; runCurrent(); first.await()
        advanceTimeBy(3_000)
        val second = async { c.caption("/b.jpg") }; runCurrent(); second.await()
        advanceTimeBy(3_000); runCurrent()
        assertEquals("one load for a batch", 1, e.loads.get()); assertEquals(0, e.releases.get())
        advanceTimeBy(2_100); runCurrent()
        assertEquals(1, e.releases.get())
    }

    @Test fun explicitReleaseFreesNowAndIsIdempotent() = runTest {
        val e = FakeEngine()
        val c = captioner(e, dispatcher = StandardTestDispatcher(testScheduler))
        val j = async { c.caption("/a.jpg") }; runCurrent(); j.await()
        val r = async { c.release(); c.release() }; runCurrent(); r.await()
        assertEquals(1, e.releases.get()); assertFalse(c.isLoaded())
    }

    @Test fun oneInferenceAtATime() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val e = FakeEngine(runMs = 20)
            val c = captioner(e, dispatcher = pool.asCoroutineDispatcher(), idleMs = 60_000)
            runBlocking { (1..6).map { async(pool.asCoroutineDispatcher()) { c.caption("/$it.jpg") } }.awaitAll().forEach { assertTrue(it.isSuccess) } ; c.release() }
            assertEquals(1, e.maxConcurrent.get()); assertEquals(6, e.runs.get()); assertEquals(1, e.loads.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun refusesOnTheMainThread() = runTest {
        val e = FakeEngine()
        val c = captioner(e, dispatcher = StandardTestDispatcher(testScheduler), main = true)
        val r = async { c.caption("/x.jpg") }; runCurrent()
        assertEquals(CaptionRefusal.MAIN_THREAD, reason(r.await()))
        assertEquals(0, e.loads.get())
    }

    @Test fun failuresAreResultsNotCrashes() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        run {
            val e = FakeEngine(failRun = true); val c = captioner(e, dispatcher = d)
            val r = async { c.caption("/x.jpg") }; runCurrent()
            assertEquals(CaptionRefusal.ENGINE_FAILED, reason(r.await())); assertEquals("a failed run drops the context", 1, e.releases.get())
        }
        run {
            val e = FakeEngine(loadError = "model load failed"); val c = captioner(e, dispatcher = d)
            val r = async { c.caption("/x.jpg") }; runCurrent(); assertEquals(CaptionRefusal.ENGINE_FAILED, reason(r.await())); assertEquals(0, e.runs.get())
        }
        run {
            val e = FakeEngine(); val c = captioner(e, models = { null }, dispatcher = d)
            val r = async { c.caption("/x.jpg") }; runCurrent(); assertEquals(CaptionRefusal.MODEL_MISSING, reason(r.await())); assertEquals(0, e.loads.get())
        }
        run {
            val e = FakeEngine(); val c = captioner(e, loader = FrameLoader { _, _ -> null }, dispatcher = d)
            val r = async { c.caption("/x.jpg") }; runCurrent(); assertEquals(CaptionRefusal.BAD_IMAGE, reason(r.await())); assertEquals(0, e.loads.get())
        }
        run {
            val e = FakeEngine(text = "   "); val c = captioner(e, dispatcher = d)
            val r = async { c.caption("/x.jpg") }; runCurrent(); assertEquals("an empty caption is a failure", CaptionRefusal.ENGINE_FAILED, reason(r.await()))
        }
    }

    @Test fun scaledSizeKeepsAspectAndNeverUpscales() {
        assertEquals(480 to 640, BitmapFrameLoader.scaledSize(480, 640, 640))
        assertEquals(480 to 640, BitmapFrameLoader.scaledSize(1080, 1440, 640))
        assertEquals(640 to 480, BitmapFrameLoader.scaledSize(4000, 3000, 640))
        assertEquals(100 to 50, BitmapFrameLoader.scaledSize(100, 50, 640))
    }

    /** The captioner and its adapters never log or print (a caption and a path are content); callers log counts and latency only. */
    @Test fun mainSourcesNeverLogOrPrint() {
        val bad = Regex("""\b(Log\.[a-z]\(|PcLog\.|println\(|print\(|Timber|printStackTrace|stackTraceToString)""")
        val src = java.io.File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(src.isNotEmpty())
        for (f in src) assertFalse(f.name, bad.containsMatchIn(f.readText().lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")))
    }
}
