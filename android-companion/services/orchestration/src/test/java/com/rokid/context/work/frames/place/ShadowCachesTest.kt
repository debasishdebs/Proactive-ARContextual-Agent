package com.rokid.context.work.frames.place

import com.rokid.context.memory.forget.DeletionGraph
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.memory.forget.lifecycle.Families
import com.rokid.context.memory.forget.lifecycle.Family
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The eager clear of the shadow's in-memory vectors (slice B1 follow-up): every deletion path empties them at once, and they are LISTED in the Forget inventory. */
class ShadowCachesTest {
    @Before @After fun reset() { ShadowCaches.resetForTests() }

    private fun counting(): Pair<() -> Unit, () -> Int> { var n = 0; return { n++; Unit } to { n } }

    @Test fun clearAllEmptiesEveryRegisteredCacheAndCountsAnEpoch() {
        val (c1, n1) = counting(); val (c2, n2) = counting()
        ShadowCaches.register("a", c1); ShadowCaches.register("b", c2)
        val e0 = ShadowCaches.epoch(); ShadowCaches.clearAll()
        assertEquals(1, n1()); assertEquals(1, n2()); assertEquals(e0 + 1, ShadowCaches.epoch())
    }

    @Test fun registeringTheSameNameReplacesSoARestartedSamplerDoesNotLeak() {
        val (c1, n1) = counting(); val (c2, n2) = counting()
        ShadowCaches.register("a", c1); ShadowCaches.register("a", c2); ShadowCaches.clearAll()
        assertEquals(0, n1()); assertEquals(1, n2()); assertEquals(setOf("a"), ShadowCaches.registered())
    }

    @Test fun aThrowingClearNeverStopsTheOthers() {
        val (c, n) = counting()
        ShadowCaches.register("a") { error("boom") }; ShadowCaches.register("b", c); ShadowCaches.clearAll()
        assertEquals(1, n())
    }

    @Test fun everyForgetListenerEventClearsThem() = runBlocking {
        val (c, n) = counting(); ShadowCaches.register("a", c)
        val l = ShadowCaches.listener()
        l.onFramesForgotten(listOf("f")); assertEquals(1, n())
        l.onRangeForgotten(0, 1, emptyList()); assertEquals(2, n())
        l.onUserForget(); assertEquals(3, n())
        l.onDeleteAll(); assertEquals(4, n())
    }

    @Test fun theListenerIsReachedThroughTheRealForgetListenerRegistry() = runBlocking {
        ForgetListeners.clearForTests()
        val (c, n) = counting(); ShadowCaches.register("a", c); ForgetListeners.register(ShadowCaches.listener())
        try { ForgetListeners.framesForgotten(listOf("f")); ForgetListeners.userForget(); ForgetListeners.deleteAll(); ForgetListeners.rangeForgotten(0, 1, emptyList()) } finally { ForgetListeners.clearForTests() }
        assertEquals(4, n())
    }

    @Test fun theRealCachesRegisterThroughTheirClearAndEmptyOnClearAll() = runBlocking {
        val sp = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        try {
            val refs = PinReferenceVectors(pinMoments = { mapOf("user:a" to "00000001") }, momentFrame = { "f1" }, photo = { ByteArray(1) }, mayEmbed = { true }, zoneBlocked = { false }, generation = { 0L }, scope = sp, elapsed = { 0L })
            val sh = PlaceShadow(embedder = { null }, photo = { null }, elapsed = { 0L })
            ShadowCaches.register(ShadowCaches.DERIVED_ITEMS[0], refs::clear); ShadowCaches.register(ShadowCaches.DERIVED_ITEMS[1], sh::clear)
            ShadowCaches.clearAll() // no throw, both reachable
            assertEquals(ShadowCaches.DERIVED_ITEMS.toSet(), ShadowCaches.registered())
        } finally { sp.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }

    @Test fun epochCountsEveryClearEvenFromManyThreads() {
        val e0 = ShadowCaches.epoch()
        val ts = List(8) { Thread { repeat(250) { ShadowCaches.clearAll() } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        assertEquals(e0 + 2_000, ShadowCaches.epoch())
    }

    @Test fun theInMemoryVectorsAreListedInTheForgetInventory() {
        assertEquals("the DeletionGraph in-memory table and the registry name the same items", ShadowCaches.DERIVED_ITEMS.toSet(), DeletionGraph.IN_MEMORY.map { it.name }.toSet())
        DeletionGraph.IN_MEMORY.forEach { assertEquals("${it.name}: Delete all clears it", DeletionGraph.Fate.WIPED, it.fate); assertTrue(it.why.isNotBlank()) }
        val holders = Families.of(Family.PLACE_CARD).holders
        assertTrue("the family lists the holders: $holders", holders.containsAll(listOf("PinReferenceVectors", "PlaceShadow")))
    }

    @Test fun everyDeletionPathCallsTheRegistry() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        // comments do not count: only code lines carry a call (the behaviour tests above and MemoryWipeTest carry the real coverage)
        fun src(rel: String) = File(root, rel).readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }.joinToString("\n") { it.substringBefore(" // ") }
        val orch = "services/orchestration/src/main/java/com/rokid/context/"
        assertTrue("Delete all clears before and after the generation bump", src(orch + "orchestration/MemoryWipe.kt").split("ShadowCaches.clearAll()").size - 1 >= 2)
        assertTrue("flag off", "ShadowCaches.clearAll()" in src(orch + "orchestration/forget/ForgetShadow.kt"))
        assertTrue("pin link set and edit", src(orch + "work/frames/place/PlacePinning.kt").split("ShadowCaches.clearAll()").size - 1 >= 2)
        assertTrue("pin sweep and pin deletion", src(orch + "work/frames/place/PinMomentLinks.kt").split("ShadowCaches.clearAll()").size - 1 >= 2)
        assertTrue("the sampler's flag read", "ShadowCaches.clearAll()" in src(orch + "work/frames/place/PlaceSamplers.kt"))
        assertTrue("the Forget listener is registered at start", "ShadowCaches.listener()" in src("phone/src/main/java/com/rokid/context/ContextApp.kt"))
    }
}
