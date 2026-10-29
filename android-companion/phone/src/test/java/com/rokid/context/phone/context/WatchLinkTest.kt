package com.rokid.context.phone.context

import android.app.Application
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.core.model.context.WatchState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** The Data Layer receiver's pure core, the active-source gate, the one-time migration, and the status and setup-step models. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class WatchLinkTest {
    private companion object { val n = AtomicInteger() }
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private fun prefs(name: String = "wl_${n.incrementAndGet()}") = ctx.getSharedPreferences(name, 0).also { it.edit().clear().commit() }

    private var now = 10_000L
    private var on = true
    private val got = ArrayList<Evidence>()
    private val link = WatchLinkState()
    private var offCounted = 0
    private var rejCounted = 0

    init { link.offSink = { offCounted++ } }

    private fun intake() = WatchMotionIntake(
        enabled = { on }, pinnedSha256 = { null }, clock = { now }, sink = { got += it; link.onAccepted((it as Evidence.WatchMotion).state, it.atMs) },
        onReject = { link.onDrop(it); rejCounted++ },
    )

    private fun msg(seq: Long, state: String = "WALKING", source: String = "pca_watch", epoch: Long = 77L, v: Int = 1) =
        """{"v":$v,"epoch":$epoch,"seq":$seq,"state":"$state","confidence":0.8,"changedAtMs":1,"sentAtMs":2,"source":"$source"}""".toByteArray()

    private fun handle(i: WatchMotionIntake?, data: ByteArray?, path: String = PcaWatchLink.PATH, sourceOn: Boolean = on) =
        PcaWatchLink.handle(path, data, sourceOn, i, link)

    @After fun tearDown() { WatchMotionIntake.current = null }

    // --- listener core: the same intake path, caller/cert gate gone, every other check kept ---

    @Test fun acceptedMessageFeedsTheSameIntakeAndSink() {
        val r = handle(intake(), msg(1))
        assertTrue(r.ok)
        assertEquals(listOf<Evidence>(Evidence.WatchMotion(10_000L, WatchState.WALKING, 0.8f)), got)
        assertEquals(WatchLinkSnapshot(10_000L, WatchState.WALKING, 1), link.snapshot.value)
    }

    @Test fun noCallerOrCertificateNeededButSourceTagIs() {
        assertEquals("bad_source", handle(intake(), msg(1, source = "galaxy_watch")).reason)
        assertTrue(got.isEmpty())
    }

    @Test fun otherIntakeChecksStayVersionMalformedSizeSeqRate() {
        val i = intake()
        assertEquals("version", handle(i, msg(1, v = 2)).reason)
        assertEquals("malformed", handle(i, "nope".toByteArray()).reason)
        assertEquals("malformed", handle(i, null).reason)
        assertEquals("too_big", handle(i, ByteArray(2049) { 'a'.code.toByte() }).reason)
        assertTrue(handle(i, msg(5)).ok)
        assertEquals("stale_seq", handle(i, msg(5)).reason)
        now += 10
        assertEquals("rate", handle(i, msg(6)).reason) // inside the 1 s window: coalesced, still reported as rate
        assertEquals(1, got.size)
    }

    @Test fun newEpochResetsSeq() {
        var resets = 0
        val i = WatchMotionIntake(enabled = { true }, pinnedSha256 = { null }, clock = { now }, sink = { got += it }, onEpochReset = { resets++ })
        assertTrue(handle(i, msg(9, epoch = 1)).ok); now += 2_000
        assertTrue(handle(i, msg(1, epoch = 2)).ok)
        assertEquals(1, resets)
    }

    @Test fun wrongPathIsRefusedAndCounted() {
        assertEquals("bad_path", handle(intake(), msg(1), path = "/pca/other").reason)
        assertEquals(1, offCounted)
    }

    // --- source gate: dropped with a counted reason, nothing stored ---

    @Test fun sourceOffDropsWithReasonAndStoresNothing() {
        val r = handle(intake(), msg(1), sourceOn = false)
        assertFalse(r.ok); assertEquals("source_off", r.reason)
        assertTrue(got.isEmpty())
        assertEquals(mapOf("source_off" to 1), link.snapshot.value.drops); assertEquals(1, offCounted)
        assertNull(link.snapshot.value.lastAcceptedAtMs)
    }

    @Test fun hubNotRunningDropsWithReason() {
        assertEquals("hub_off", handle(null, msg(1)).reason)
        assertEquals(1, link.snapshot.value.rejected)
    }

    @Test fun intakeDisabledByTheSettingIsCountedThroughOnReject() {
        on = false
        assertEquals("not_enabled", handle(intake(), msg(1), sourceOn = true).reason)
        assertEquals(1, rejCounted); assertTrue(got.isEmpty())
    }

    @Test fun providerWithSourceOffIsDroppedAndCounted() {
        WatchLinkState.shared.offSink = { offCounted++ }
        WatchMotionIntake.current = null
        val before = WatchLinkState.shared.snapshot.value.drops["source_off"] ?: 0
        val b: Bundle = WatchMotionProvider.decide("com.livear.fit", "{}") { _, _ -> true }
        assertEquals("not_enabled", b.getString("reason"))
        assertEquals(before + 1, WatchLinkState.shared.snapshot.value.drops["source_off"])
        assertEquals(1, offCounted)
        WatchLinkState.shared.offSink = {}
    }

    @Test fun watchSourceRecordsAcceptsAndDrops() {
        val s = ContextSourceSettings(prefs()); s.activeWatchSource = ContextSourceSettings.WATCH_APP
        val sunk = ArrayList<Evidence>()
        val src = WatchSource(s, { true }, { EvidenceSink { sunk += it } }, onRejected = { rejCounted++ }, clock = { now }, link = link)
        assertTrue(src.start())
        val i = WatchMotionIntake.current!!
        assertTrue(PcaWatchLink.handle(PcaWatchLink.PATH, msg(1), true, i, link).ok)
        assertEquals(1, sunk.size); assertEquals(1, src.events)
        assertEquals("stale_seq", PcaWatchLink.handle(PcaWatchLink.PATH, msg(1), true, i, link).reason)
        assertEquals(1, rejCounted)
        src.stop()
    }

    // --- active source setting and migration ---

    @Test fun activeSourceDefaultsToNone() {
        assertEquals("none", ContextSourceSettings(prefs()).activeWatchSource)
    }

    @Test fun activeSourceRoundTrip() {
        val s = ContextSourceSettings(prefs())
        s.activeWatchSource = "watch_app"; assertTrue(s.enabled("watch_app")); assertEquals("watch_app", s.activeWatchSource)
        s.activeWatchSource = "none"; assertFalse(s.enabled("watch_app"))
    }

    @Test fun oldLiveFitToggleAndPinMigrateToNone() {
        val p = prefs("legacy")
        p.edit().putBoolean("src_watch_livefit", true).putString("livefit_cert_sha256", "ab".repeat(32)).putBoolean("src_phone_car", true).commit()
        val s = ContextSourceSettings(p)
        assertEquals("none", s.activeWatchSource); assertFalse(s.enabled("watch_app"))
        assertFalse(p.contains("src_watch_livefit")); assertNull(s.liveFitCertSha256)
        assertTrue(s.enabled("phone_car")) // other sources untouched
    }

    @Test fun migrationIsOneTimeSoARealOptInSurvivesARestart() {
        val p = prefs("legacy2")
        p.edit().putBoolean("src_watch_livefit", true).commit()
        ContextSourceSettings(p).activeWatchSource = "watch_app"
        assertEquals("watch_app", ContextSourceSettings(p).activeWatchSource)
    }

    @Test fun deleteAllTurnsTheWatchSourceOff() {
        val s = ContextSourceSettings(prefs()); s.activeWatchSource = "watch_app"; s.clearPersonal()
        assertEquals("none", s.activeWatchSource)
    }

    // --- status row model, every state ---

    private val t = { ms: Long -> "T$ms" }
    private val min = 60_000L
    private fun row(on: Boolean = true, nodes: WatchNodes? = null, snap: WatchLinkSnapshot = WatchLinkSnapshot(), nowMs: Long = 100 * min) =
        WatchStatusModel.of(on, nodes, snap, nowMs, t)

    @Test fun statusSourceOffShowsNothing() {
        assertEquals(WatchStatusRow(WatchStatusKind.OFF, ""), row(on = false, snap = WatchLinkSnapshot(1, WatchState.WALKING, 1)))
    }

    @Test fun statusNotInstalledOrNotPaired() {
        assertEquals("Watch app not installed or not paired", row(nodes = WatchNodes(0, 0)).text)
        assertEquals(WatchStatusKind.NOT_INSTALLED, row(nodes = WatchNodes(1, 0)).kind)
    }

    @Test fun statusNotReachableWhenTheQueryFailsAndNothingRecent() {
        assertEquals("Watch app not reachable", row(nodes = WatchNodes(0, 0, failed = true)).text)
        val recent = WatchLinkSnapshot(99 * min, WatchState.STILL, 1)
        assertEquals(WatchStatusKind.LAST_MOTION, row(nodes = WatchNodes(0, 0, failed = true), snap = recent).kind)
    }

    @Test fun statusNoDataInTheLast15MinBoundary() {
        val ok = WatchNodes(1, 1)
        assertEquals("Watch: no data in the last 15 min", row(nodes = ok).text)
        assertEquals("Watch: no data in the last 15 min", row(nodes = null).text)
        assertEquals(WatchStatusKind.LAST_MOTION, row(nodes = ok, snap = WatchLinkSnapshot(85 * min, WatchState.WALKING, 1)).kind) // exactly 15 min
        assertEquals(WatchStatusKind.NO_DATA, row(nodes = ok, snap = WatchLinkSnapshot(85 * min - 1, WatchState.WALKING, 1)).kind)
    }

    @Test fun statusLastMotionShowsStateAndTime() {
        val r = row(nodes = WatchNodes(1, 1), snap = WatchLinkSnapshot(95 * min, WatchState.RUNNING, 3))
        assertEquals("Last motion: RUNNING, T${95 * min}", r.text)
    }

    // --- setup step model ---

    @Test fun setupStepWaitsThenConfirmsOnTheFirstAcceptedMessage() {
        assertEquals("Waiting for the watch\u2026", WatchLinkStep.line(WatchLinkStep.confirmed(WatchLinkSnapshot())))
        // only drops: still waiting
        assertFalse(WatchLinkStep.confirmed(WatchLinkSnapshot(drops = mapOf("stale_seq" to 2))))
        assertEquals("Watch link confirmed", WatchLinkStep.line(WatchLinkStep.confirmed(WatchLinkSnapshot(1, WatchState.STILL, 1))))
    }
}
