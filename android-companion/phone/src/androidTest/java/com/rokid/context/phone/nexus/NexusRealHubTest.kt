package com.rokid.context.phone.nexus

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.HudHost
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.HudMapper
import io.github.pca.nexusport.NexusActionHandler
import io.github.pca.nexusport.NexusLimits
import io.github.pca.nexusport.NexusPort
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Outcome
import io.github.pca.nexusport.TripReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S1.5b: PCA's real Nexus provider, sink and port against the REAL Nexus hub on the emulator (hub debug build v2.6.0, the bridge APK built from this tree), with TEST gates in place of the
 * glasses-driven ones. Nothing here is compiled into an APK a user installs: this file lives in the androidTest source set only.
 *
 * The provider, sink, bridge link and wipe ledger are the production classes, wired as [NexusGraph.attach] wires them: the gates are [PcaNexusGates] over test lambdas (worn, paused, the two zone
 * fences, provenance), the ledger is a [NexusHostStore] on a private prefs file, the bridge binding is the real [ServiceBridgeLink] (one thin wrapper counts the binder calls, it forwards every
 * transaction untouched). The hub's state (approved, a capability unticked, force-stopped) is arranged from outside by adb; the expected answer is passed as an instrumentation argument (`expect`).
 * Results are logged with the tag `PCAREALHUB` (codes, counts and states only, never text).
 */
/**
 * WARNING: EMULATOR ONLY. These tests run `am force-stop` on the Nexus hub. Gradle's connectedDebugAndroidTest installs and runs on EVERY attached device, so never run it while the real phone or
 * glasses are attached. The class skips itself (Assume) unless the device is an emulator.
 */
@RunWith(AndroidJUnit4::class)
class NexusRealHubTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val rigs = mutableListOf<Rig>()

    @Before fun bridgePresent() {
        val id = (Build.FINGERPRINT + "|" + Build.PRODUCT + "|" + Build.HARDWARE).lowercase()
        assumeTrue("emulator only: this test force-stops the hub", listOf("generic", "emulator", "sdk_gphone", "ranchu").any { id.contains(it) })
        val ok = try { ctx.packageManager.getPackageInfo(NexusPackages.BRIDGE, 0); true } catch (_: Exception) { false }
        assumeTrue("bridge APK not installed", ok)
    }

    @After fun tearDown() { rigs.forEach { say("RIGLOG " + it.logs.filter { l -> l.startsWith("nexus trip") || l.startsWith("nexus forget") || l.startsWith("nexus wipe") || l.startsWith("nexus unbound") || l.contains("unhealthy") || l.contains("dirty") }.joinToString(";") + " owed=${it.store.owed()}"); it.close() }; rigs.clear() }

    // ---- the rig ----

    /** A binder that forwards every transaction and records its code (the number of attempts the sink made at the hub through the bridge). */
    private class CountingBinder(private val real: IBinder, private val counts: ConcurrentHashMap<Int, AtomicInteger>) : IBinder by real {
        override fun transact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
            counts.getOrPut(code) { AtomicInteger() }.incrementAndGet()
            return real.transact(code, data, reply, flags)
        }
    }

    private class CountingLink(private val inner: BridgeLink, val counts: ConcurrentHashMap<Int, AtomicInteger>) : BridgeLink by inner {
        override fun bind(onConnected: (IBinder) -> Unit, onGone: () -> Unit) = inner.bind({ b -> onConnected(CountingBinder(b, counts)) }, onGone)
    }

    /** The test gates: every flag starts as "everything open". [liveWait] makes the provenance gate block (a delivery in flight) until released. */
    class Flags {
        @Volatile var worn = true
        @Volatile var paused = false
        @Volatile var zone = false
        @Volatile var askZone = false
        @Volatile var live = true
        @Volatile var liveWait: CountDownLatch? = null
        @Volatile var liveEntered: CountDownLatch? = null
    }

    private inner class Rig {
        val flags = Flags()
        val counts = ConcurrentHashMap<Int, AtomicInteger>()
        val logs = CopyOnWriteArrayList<String>()
        val prefs = ctx.getSharedPreferences("nexus_realhub_test", 0).also { it.edit().clear().commit() }
        val store = NexusHostStore(prefs).also { it.set(HudHost.NEXUS) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gates = PcaNexusGates(
            sourcesLive = { _ ->
                flags.liveEntered?.countDown()
                flags.liveWait?.await(10, TimeUnit.SECONDS)
                flags.live
            },
            zoneFence = { flags.zone }, askFence = { flags.askZone }, paused = { flags.paused }, worn = { flags.worn },
        )
        val actions = object : NexusActionHandler { override fun mark(ref: String) = Unit; override fun more(ref: String) = Unit }
        val provider = NexusProvider(
            CountingLink(ServiceBridgeLink(ctx.applicationContext), counts), store::current, gates, actions, scope,
            log = { logs.add(it) }, debt = store,
        )

        fun connect(timeoutMs: Long = 15_000) {
            provider.refresh()
            val end = System.currentTimeMillis() + timeoutMs
            while (!provider.bound.value && System.currentTimeMillis() < end) Thread.sleep(100)
            assertTrue("bridge did not bind (bind=${provider.bind.value})", provider.bound.value)
            Thread.sleep(1500) // the session handshake and the first feature push settle
        }

        fun close() { try { provider.trip(TripReason.LINK_LOST) } catch (_: Exception) { }; scope.cancel(); prefs.edit().clear().commit() }
        fun transactions(name: String): Int = counts[codeOf(name)]?.get() ?: 0
        fun showAttempts() = transactions("TRANSACTION_showIn")
        fun totalAttempts() = counts.values.sumOf { it.get() }
        fun show(e: HudEvent): Outcome? = runBlocking { provider.show(e) }
    }

    private fun rig(): Rig = Rig().also { rigs += it }

    private fun codeOf(field: String): Int = try {
        NexusPort.Stub::class.java.getDeclaredField(field).apply { isAccessible = true }.getInt(null)
    } catch (_: Exception) { -1 }

    private fun say(s: String) { Log.i(TAG, s) }

    private fun describe(o: Outcome?): String = when (o) {
        null -> "NOT_SENT(host or bridge)"
        is Outcome.Shown -> "SHOWN"
        is Outcome.Dropped -> if (o.reason == io.github.pca.nexusport.DropReason.TRANSPORT) "DROPPED_TRANSPORT_" + NexusResult.name(o.code) else "DROPPED_" + o.reason.name
    }

    private fun events(ttl: Long = 6_000): List<Pair<String, HudEvent>> = listOf(
        "nudge" to HudEvent.Nudge("n-1", "Nudge", "A short nudge body", setOf("mem:test-1"), ttl),
        "place" to HudEvent.PlaceCard("p-1", "Place", "A short place card body", setOf("mem:test-2"), ttl),
        "answer" to HudEvent.AskAnswer("a-1", "A short answer", listOf("Notes"), setOf("mem:test-3"), ttl),
        "mark" to HudEvent.MarkConfirm("m-1", "paused", emptySet(), ttl),
    )

    private fun shell(cmd: String): String {
        val fd = inst.uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    private fun expectCode(): Int? = args.getString("expect")?.let { n -> (0..10).firstOrNull { NexusResult.name(it) == n } ?: error("unknown expect=$n") }

    private fun transportCode(o: Outcome?): Int? = (o as? Outcome.Dropped)?.takeIf { it.reason == io.github.pca.nexusport.DropReason.TRANSPORT }?.code ?: if (o is Outcome.Shown) NexusResult.OK else null

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(100) }
        return cond()
    }

    // ---- (1) the hub's answer to each kind of publish, and a quiet give-up ----

    @Test fun publishRecordsHubAnswer() {
        val r = rig(); r.connect()
        val expect = expectCode()
        val seen = mutableListOf<String>()
        for ((name, e) in events()) {
            val before = r.showAttempts()
            val o = r.show(e)
            val attempts = r.showAttempts() - before
            seen += "$name=${describe(o)}/attempts=$attempts"
            assertTrue("$name: more than one attempt at the hub ($attempts): retry storm", attempts <= 1)
            if (expect != null) assertEquals("$name: the hub's code", expect, transportCode(o))
        }
        val quiet = r.showAttempts()
        Thread.sleep(8_000)   // nothing retries in the background
        assertEquals("a background retry went out after the give-up", quiet, r.showAttempts())
        val state = NexusStatus.of(NexusInputs(NexusInstalled(bridge = true, hub = true, rokidCoder = false), hostIsNexus = true, bound = r.provider.bound.value, lastCode = r.provider.lastCode.value))
        say("RESULT publish expect=${args.getString("expect")} ${seen.joinToString(" ")} lastCode=${r.provider.lastCode.value?.let { NexusResult.name(it) }} status=\"${NexusStatus.title(state)}\" totalBinderCalls=${r.totalAttempts()} showAttempts=${r.showAttempts()} bind=${r.provider.bind.value}")
        assertTrue("no give-up line", r.logs.none { it.contains("failed") && !it.contains("nexus wipe record") && it.contains("exception", true) })
    }

    // ---- (1b) the hub is force-stopped: the first push, then recovery ----

    @Test fun hubForceStopThenPublish() {
        val r = rig(); r.connect()
        val hub = NexusPackages.HUB
        val seq = mutableListOf<String>()
        val base = r.show(events().first().second)
        seq += "before=${describe(base)}"
        say("force-stop hub: " + shell("am force-stop $hub").trim())
        var attemptsBefore = r.showAttempts()
        val t0 = System.currentTimeMillis()
        Thread.sleep(300)
        for (i in 1..20) {
            val o = r.show(HudEvent.Nudge("n-fs-$i", "Nudge", "After the hub stopped", setOf("mem:test-1"), 6_000))
            seq += "t+${System.currentTimeMillis() - t0}ms=${describe(o)}"
            Thread.sleep(400)
        }
        val attempts = r.showAttempts() - attemptsBefore
        say("RESULT forcestop expect=${args.getString("expect")} ${seq.joinToString(" ")} publishes=20 showAttempts=$attempts totalBinderCalls=${r.totalAttempts()} lastCode=${r.provider.lastCode.value?.let { NexusResult.name(it) }}")
        assertTrue("more than one attempt per publish ($attempts for 20)", attempts <= 20)
    }

    // ---- (2) withdrawal and the history wipe ----

    @Test fun closeEventsWithdrawAndWipe() {
        val r = rig(); r.connect()
        val report = mutableListOf<String>()
        // The close events that only withdraw: no wipe owed.
        for (reason in listOf(TripReason.PAUSE, TripReason.ZONE_HOLD, TripReason.NOT_WORN)) {
            val o = r.show(HudEvent.Nudge("n-w-${reason.name}", "Nudge", "A body", setOf("mem:test-1"), 20_000))
            val trips0 = r.transactions("TRANSACTION_tripIn"); val gen0 = r.store.generation()
            r.provider.trip(reason)
            val cleared = waitFor(4_000) { r.provider.liveSlots().isEmpty() }
            val tripSeen = waitFor(3_000) { r.transactions("TRANSACTION_tripIn") > trips0 }
            report += "${reason.name}:publish=${describe(o)},slotsEmpty=$cleared,epochToBridge=$tripSeen,owed=${r.store.owed()},genMoved=${r.store.generation() != gen0}"
            assertTrue("$reason: a notice stayed live", cleared)
            assertTrue("$reason: the bridge was not told of the trip", tripSeen)
            assertFalse("$reason: no wipe is owed for it", r.store.owed()); assertEquals(gen0, r.store.generation())
        }
        // The close events that owe a wipe: owed first (persisted), the hub acknowledges, the debt is cleared.
        for (reason in listOf(TripReason.FORGET, TripReason.DELETE_ALL)) {
            val o = r.show(HudEvent.Nudge("n-w-${reason.name}", "Nudge", "A body", setOf("mem:test-1"), 20_000))
            val gen0 = r.store.generation(); val ack0 = r.logs.count { it == "nexus wipe acknowledged" }
            r.provider.trip(reason)
            assertTrue("$reason: a new debt generation", r.store.generation() > gen0)   // recorded synchronously, before anything is sent (the provider and the sink each record one: the generation is monotonic, an ack covers the newer)
            val cleared = waitFor(4_000) { r.provider.liveSlots().isEmpty() }
            val acked = waitFor(15_000) { r.logs.count { it == "nexus wipe acknowledged" } > ack0 }
            val done = waitFor(5_000) { !r.store.owed() }
            report += "${reason.name}:publish=${describe(o)},slotsEmpty=$cleared,forgetCode=${r.logs.lastOrNull { it.startsWith("nexus forget history code=") }?.substringAfterLast('=')},acked=$acked,debtCleared=$done"
            assertTrue("$reason: slots", cleared); assertTrue("$reason: not acknowledged by the hub", acked); assertTrue("$reason: the debt was not cleared", done)
        }
        // Mode off (the host switch): a wipe is owed, the hub acknowledges, the bridge is let go.
        val gen0 = r.store.generation(); val ack0 = r.logs.count { it == "nexus wipe acknowledged" }
        r.store.set(HudHost.PCA)
        assertTrue("MODE_OFF: debt written with the host switch", r.store.generation() > gen0)
        assertTrue(r.store.owed())
        r.provider.refresh()
        val acked = waitFor(15_000) { r.logs.count { it == "nexus wipe acknowledged" } > ack0 }
        val unbound = waitFor(15_000) { !r.provider.bound.value && r.logs.contains("nexus unbound") }
        val done = waitFor(5_000) { !r.store.owed() }
        report += "MODE_OFF:acked=$acked,unbound=$unbound,debtCleared=$done"
        assertTrue("MODE_OFF: ack", acked); assertTrue("MODE_OFF: unbound", unbound); assertTrue("MODE_OFF: cleared", done)
        say("RESULT withdraw expect=${args.getString("expect")} ${report.joinToString(" | ")} logs=${r.logs.filter { it.startsWith("nexus trip") || it.startsWith("nexus forget") || it.startsWith("nexus wipe") || it.startsWith("nexus unbound") }.joinToString(";")}")
    }

    /** The wipe owed while the hub could not answer (it was force-stopped) is still owed and is completed when it is back. */
    @Test fun wipeOwedWhileHubDownCompletes() {
        val r = rig(); r.connect()
        say("force-stop hub: " + shell("am force-stop ${NexusPackages.HUB}").trim())
        val gen0 = r.store.generation(); val ack0 = r.logs.count { it == "nexus wipe acknowledged" }
        r.provider.trip(TripReason.FORGET)
        val owedAtOnce = r.store.owed() || r.logs.count { it == "nexus wipe acknowledged" } > ack0
        val acked = waitFor(60_000) { r.logs.count { it == "nexus wipe acknowledged" } > ack0 }
        val done = waitFor(5_000) { !r.store.owed() }
        say("RESULT wipeHubDown owedAtOnce=$owedAtOnce genMoved=${r.store.generation() > gen0} acked=$acked debtCleared=$done forget=${r.logs.filter { it.startsWith("nexus forget history code=") }.joinToString(",") { it.substringAfterLast('=') }}")
        assertTrue(r.store.generation() > gen0); assertTrue("not acknowledged within 60 s", acked); assertTrue(done)
    }

    // ---- (3) text limits ----

    @Test fun textLimits() {
        val r = rig(); r.connect()
        val base = r.show(HudEvent.Nudge("n-t-0", "Nudge", "short", setOf("mem:test-1"), 4_000))
        val baseline = transportCode(base)
        val cases = linkedMapOf(
            "body1024" to ("T" to "b".repeat(1024)),
            "body1025" to ("T" to "b".repeat(1025)),
            "body5000" to ("T" to "b".repeat(5000)),
            "title100" to ("t".repeat(100) to "body"),
            "lines40" to ("T" to (1..40).joinToString("\n") { "line $it" }),
            "surrogateAtCut" to ("T" to "c".repeat(1022) + "😀" + "tail"),
            "empty" to ("T" to ""),
        )
        val out = mutableListOf("baseline=${describe(base)}")
        for ((name, tb) in cases) {
            val ev = HudEvent.Nudge("n-t-$name", tb.first, tb.second, setOf("mem:test-1"), 4_000)
            val cmd = HudMapper.map(ev)!!.command
            assertTrue("$name: mapper text over the limit (${cmd.text.length})", cmd.text.length <= NexusLimits.NOTICE_TEXT_CHARS)
            assertTrue("$name: mapper title over the limit", cmd.title.length <= NexusLimits.NOTICE_TITLE_CHARS)
            assertTrue("$name: mapper lines over the limit", cmd.text.split('\n').size <= NexusLimits.NOTICE_LINES)
            assertFalse("$name: a split surrogate", cmd.text.isNotEmpty() && Character.isHighSurrogate(cmd.text.last()))
            val o = r.show(ev)
            out += "$name(len=${cmd.text.length})=${describe(o)}"
            val c = transportCode(o)
            assertTrue("$name: the hub said INVALID or the call failed ($c)", c != NexusResult.INVALID && c != NexusResult.ERROR)
            assertEquals("$name: same hub answer as a short text", baseline, c)
            Thread.sleep(300)
        }
        say("RESULT textLimits expect=${args.getString("expect")} ${out.joinToString(" ")} showAttempts=${r.showAttempts()}")
    }

    // ---- (4) the privacy lane ----

    @Test fun privacyLaneNothingSentWhileClosed() {
        val r = rig(); r.connect()
        val report = mutableListOf<String>()
        val gateNames = listOf("paused", "zone", "askZone", "notWorn", "provenance")
        fun close(g: String) = with(r.flags) { when (g) { "paused" -> paused = true; "zone" -> zone = true; "askZone" -> askZone = true; "notWorn" -> worn = false; else -> live = false } }
        fun open() = with(r.flags) { paused = false; zone = false; askZone = false; worn = true; live = true }
        for (g in gateNames) {
            open(); close(g)
            val before = r.showAttempts()
            val results = events().map { (n, e) -> n to describe(r.show(e)) }
            val sent = r.showAttempts() - before
            // A paused phone still lets the content-free mark token through (the sink's design); everything with content is dropped. Every other gate drops all of them (a mark has no sources, so provenance does not touch it).
            val allowedSent = if (g == "paused" || g == "provenance") 1 else 0
            report += "$g:dropped=${results.joinToString(",") { "${it.first}=${it.second}" }},binderShows=$sent"
            assertTrue("$g closed: $sent publishes reached the bridge (allowed $allowedSent)", sent <= allowedSent)
            results.forEach { (n, d) -> if (!(allowedSent == 1 && n == "mark")) assertTrue("$g closed: $n was not dropped ($d)", d.startsWith("DROPPED_") && !d.startsWith("DROPPED_TRANSPORT")) }
            // Flipped mid-flight: the provenance gate is held open while the delivery is in flight, the gate flips, then the delivery continues.
            open()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            r.flags.liveEntered = entered; r.flags.liveWait = release
            val mid = before(r) {
                var o: Outcome? = null
                val t = Thread { o = r.show(HudEvent.Nudge("n-mid-$g", "Nudge", "mid-flight", setOf("mem:test-1"), 6_000)) }.apply { start() }
                assertTrue("the delivery never reached the provenance gate", entered.await(10, TimeUnit.SECONDS))
                close(g)
                release.countDown(); t.join(15_000)
                o
            }
            r.flags.liveEntered = null; r.flags.liveWait = null
            val midSent = r.showAttempts() - mid.first
            report += "$g(mid-flight)=${describe(mid.second)},binderShows=$midSent"
            if (g != "provenance") {
                assertEquals("$g flipped mid-flight: a publish went out", 0, midSent)
                assertTrue("$g flipped mid-flight: not dropped (${describe(mid.second)})", describe(mid.second).let { it.startsWith("DROPPED_") && !it.startsWith("DROPPED_TRANSPORT") })
            } else assertEquals("provenance lost mid-flight: a publish went out", 0, midSent)
            open()
        }
        // Control: with everything open again, a publish does reach the hub (so the zero counts above are the gates, not a dead rig).
        open()
        val b = r.showAttempts()
        val ctl = r.show(HudEvent.Nudge("n-ctl", "Nudge", "control", setOf("mem:test-1"), 4_000))
        assertEquals("control publish did not reach the bridge", b + 1, r.showAttempts())
        report += "control=${describe(ctl)}"
        say("RESULT privacy expect=${args.getString("expect")} ${report.joinToString(" | ")}")
    }

    private inline fun before(r: Rig, block: () -> Outcome?): Pair<Int, Outcome?> { val b = r.showAttempts(); return b to block() }

    companion object { const val TAG = "PCAREALHUB" }
}
