package com.rokid.context.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Overlay
import com.rokid.context.glasses.capture.ForegroundFallback
import com.rokid.context.glasses.capture.HudPresence
import com.rokid.context.glasses.capture.OutboundBuffer
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FrameUplink
import com.rokid.context.glasses.link.GlassesBridge
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.link.SharedBridge
import com.rokid.context.glasses.pause.GlassesPauses
import com.rokid.context.glasses.pause.PauseStore
import com.rokid.context.glasses.pause.PauseToggle
import com.rokid.context.glasses.voice.AudioRecordSource
import com.rokid.context.glasses.voice.PcmSource
import com.rokid.context.glasses.voice.PttLink
import com.rokid.context.glasses.voice.PushToTalk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide glasses state shared by [MainActivity] and the capture service: pause set, outbound buffer, uplink,
 * push-to-talk and the current [HudController]. A controller exists only while the activity does, i.e. while the CXR
 * session can be open (spec §1.7), so nothing is sent without one.
 */
class GlassesRuntime private constructor(private val app: Context) {
    /** Nothing launched here may crash the glasses app; only the exception's simple name is logged. */
    private val errors = CoroutineExceptionHandler { _, e -> Log.w(TAG, "runtime job failed: ${e.javaClass.simpleName}") }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    val pauses = GlassesPauses(PauseStore(File(app.filesDir, "pauses.preferences_pb"), ioScope))
    val buffer = OutboundBuffer(File(app.filesDir, "outbound"), log = { Log.i(UPLINK_TAG, it) })
    /** Our HUD activity's lifecycle, for the capture fallback (spec §1.7: never reopened over another app). */
    val hudPresence = HudPresence()
    val fallback = ForegroundFallback(hudPresence)
    /** Push-to-talk recording ([ptt] sets it); frames never flush while true (spec §1.8). */
    val listening = MutableStateFlow(false)
    /** The attached controller (tests check that exactly one is live). */
    @Volatile internal var controller: HudController? = null
        private set
    private var attachJob: Job? = null
    /** Process-wide: built once from the application context, never per activity (CXR-S cannot unsubscribe). */
    val bridge: SharedBridge by lazy { SharedBridge(bridgeFactory(app)) }
    val uplink = FrameUplink(scope, buffer, sendChunk = { c -> controller?.sendFrameChunk(c) ?: -1 }, canSend = ::canSend, log = { Log.i(UPLINK_TAG, it) })

    /** Tap = Ask (spec §5.1). Only an open session takes it; capture pauses don't matter (Ask is a user action). */
    val ptt = PushToTalk(
        link = object : PttLink {
            override fun start(): Boolean = controller?.takeIf { it.sessionOpen.value }?.send(Command.ListenStart) == true
            override fun audio(base64: String): Boolean = controller?.sendAudio(base64) == true
            override fun end() { controller?.sendListenEnd() }
        },
        recording = listening,
        hasPermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        sourceFactory = { pcmSourceFactory() },
        log = { Log.i(TAG, "ptt: $it") },
    )

    /** Session open, not listening, and the phone is not showing Listening (flush resumes once the answer is up). */
    private fun canSend(): Boolean {
        val c = controller ?: return false
        return c.sessionOpen.value && !listening.value && c.frame.value?.overlay !is Overlay.Listening
    }

    fun attach(c: HudController) {
        controller = c
        c.onFrameAck = uplink::onAck
        // Hello goes out first; give the phone a moment to take the clock offset before frames follow.
        c.onSessionOpened = { scope.launch { delay(FLUSH_AFTER_HELLO_MS); uplink.kick() } }
        c.onPhonePauses = { records -> scope.launch { pauses.mergeFromPhone(records) } }
        attachJob?.cancel()
        attachJob = scope.launch {
            launch {
                combine(c.frame, listening) { f, l -> l || f?.overlay is Overlay.Listening }
                    .distinctUntilChanged()
                    .collect { busy -> if (!busy) uplink.kick() }
            }
            // A closed session can't take the rest of an utterance: stop recording, no pc_listen_end.
            launch { c.sessionOpen.collect { open -> if (!open) ptt.cancel() } }
        }
    }

    /** [c]'s activity is gone: its callbacks go quiet; if it is current, the bridge stops calling it too. */
    fun detach(c: HudController) {
        c.onFrameAck = {}
        c.onSessionOpened = {}
        c.onPhonePauses = {}
        if (controller !== c) return
        ptt.cancel()
        controller = null
        attachJob?.cancel()
        bridge.release()
    }

    /** `pc_hello`: clock, battery, buffer size, local pauses, unconfirmed resumes, next capture (all glasses clock). */
    fun hello(nextCaptureAtMs: Long?): Hello = Hello(
        glassesBatteryPct = batteryPct(),
        bufferSize = buffer.size,
        glassesNowMs = System.currentTimeMillis(),
        pauses = pauses.records.value,
        nextCaptureAtMs = nextCaptureAtMs,
        resumedAtMs = pauses.resumedAtMs(),
    )

    /** Serialises a captured frame's write with the pause toggle's buffer clear (spec §5.5). */
    private val captureGate = Any()

    /**
     * Stores a captured frame only while capture is allowed. Checked again under [captureGate], because the camera
     * window lasts seconds after CaptureService's first check: a double-tap pause clears under the same gate after
     * recording the pause, so either this sees the pause or the clear removes the frame. A pause that arrives during
     * the write (a phone merge) is caught by the second check.
     */
    fun bufferCaptured(frameId: String, jpeg: ByteArray, capturedAtMs: Long, luma: Int?, phash: String?, batteryPct: Int?): BufferResult =
        synchronized(captureGate) {
            if (!pauses.captureAllowed()) return BufferResult.PAUSED
            if (!buffer.add(frameId, jpeg, capturedAtMs, luma, phash, batteryPct)) return BufferResult.NOT_STORED
            if (!pauses.captureAllowed()) { buffer.remove(frameId); return BufferResult.PAUSED }
            BufferResult.STORED
        }

    /** A frame was buffered: report the new buffer size and flush if the session is open. */
    fun onFrameBuffered() {
        controller?.sendHello()
        uplink.kick()
    }

    /** The next capture is armed: tell the phone its new time (the hello from [onFrameBuffered] still has the old one). */
    fun onCaptureScheduled() {
        controller?.sendHello()
    }

    /** Double-tap pause/resume, applied here at once (spec §5.5); a pause drops the buffered frames. */
    suspend fun togglePause(): PauseToggle {
        val t = pauses.toggle()
        if (t is PauseToggle.Paused) synchronized(captureGate) { buffer.clear() }
        return t
    }

    fun batteryPct(): Int? = app.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    companion object {
        const val TAG = "PcGlasses"
        const val UPLINK_TAG = "PcUplink"
        const val FLUSH_AFTER_HELLO_MS = 500L
        @Volatile private var instance: GlassesRuntime? = null
        fun get(context: Context): GlassesRuntime =
            instance ?: synchronized(this) { instance ?: GlassesRuntime(context.applicationContext).also { instance = it } }

        /** Test seam: the CXR bridge (a fake in Robolectric tests). */
        @Volatile internal var bridgeFactory: (Context) -> GlassesBridge = { CxrBridge(it) }

        /** Test seam: the glasses mic (a fake in Robolectric tests; preflight F23, same pattern as [bridgeFactory]). */
        @VisibleForTesting
        @Volatile internal var pcmSourceFactory: () -> PcmSource = { AudioRecordSource() }

        /** Drops the singleton and stops its jobs (so the next test's PauseStore owns its file alone). */
        internal fun resetForTests() = synchronized(this) {
            instance?.let { it.ptt.cancel(); it.scope.cancel(); it.ioScope.cancel() }
            instance = null
        }
    }
}

/** [GlassesRuntime.bufferCaptured]: NOT_STORED = the write failed (e.g. storage full). */
enum class BufferResult { STORED, PAUSED, NOT_STORED }
