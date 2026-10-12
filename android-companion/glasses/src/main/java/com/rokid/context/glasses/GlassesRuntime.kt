package com.rokid.context.glasses

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Overlay
import com.rokid.context.glasses.capture.OutboundBuffer
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FrameUplink
import com.rokid.context.glasses.link.GlassesBridge
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.link.SharedBridge
import com.rokid.context.glasses.pause.GlassesPauses
import com.rokid.context.glasses.pause.PauseStore
import com.rokid.context.glasses.pause.PauseToggle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide glasses state shared by [MainActivity] and the capture service: pause set, outbound buffer, uplink and
 * the current [HudController]. A controller exists only while the activity does, i.e. while the CXR session can be
 * open (spec §1.7), so nothing is sent without one.
 */
class GlassesRuntime private constructor(private val app: Context) {
    /** Nothing launched here may crash the glasses app; only the exception's simple name is logged. */
    private val errors = CoroutineExceptionHandler { _, e -> Log.w(TAG, "runtime job failed: ${e.javaClass.simpleName}") }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    val pauses = GlassesPauses(PauseStore(File(app.filesDir, "pauses.preferences_pb"), ioScope))
    val buffer = OutboundBuffer(File(app.filesDir, "outbound"), log = { Log.i(UPLINK_TAG, it) })
    /** Push-to-talk recording (set by Phase 6); frames never flush while true (spec §1.8). */
    val listening = MutableStateFlow(false)
    /** The attached controller (tests check that exactly one is live). */
    @Volatile internal var controller: HudController? = null
        private set
    private var attachJob: Job? = null
    /** Process-wide: built once from the application context, never per activity (CXR-S cannot unsubscribe). */
    val bridge: SharedBridge by lazy { SharedBridge(bridgeFactory(app)) }
    val uplink = FrameUplink(scope, buffer, sendChunk = { c -> controller?.sendFrameChunk(c) ?: -1 }, canSend = ::canSend, log = { Log.i(UPLINK_TAG, it) })

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
            combine(c.frame, listening) { f, l -> l || f?.overlay is Overlay.Listening }
                .distinctUntilChanged()
                .collect { busy -> if (!busy) uplink.kick() }
        }
    }

    /** [c]'s activity is gone: its callbacks go quiet; if it is current, the bridge stops calling it too. */
    fun detach(c: HudController) {
        c.onFrameAck = {}
        c.onSessionOpened = {}
        c.onPhonePauses = {}
        if (controller !== c) return
        controller = null
        attachJob?.cancel()
        bridge.release()
    }

    /** `pc_hello`: clock, battery, buffer size, local pauses, next capture (all glasses clock). */
    fun hello(nextCaptureAtMs: Long?): Hello = Hello(
        glassesBatteryPct = batteryPct(),
        bufferSize = buffer.size,
        glassesNowMs = System.currentTimeMillis(),
        pauses = pauses.records.value,
        nextCaptureAtMs = nextCaptureAtMs,
    )

    /** A frame was buffered: report the new buffer size and flush if the session is open. */
    fun onFrameBuffered() {
        controller?.sendHello()
        uplink.kick()
    }

    /** Double-tap pause/resume, applied here at once (spec §5.5); a pause drops the buffered frames. */
    suspend fun togglePause(): PauseToggle {
        val t = pauses.toggle()
        if (t is PauseToggle.Paused) buffer.clear()
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

        /** Drops the singleton and stops its jobs (so the next test's PauseStore owns its file alone). */
        internal fun resetForTests() = synchronized(this) {
            instance?.let { it.scope.cancel(); it.ioScope.cancel() }
            instance = null
        }
    }
}
