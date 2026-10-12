package com.rokid.context.glasses.link

import android.util.Log
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.StateFrame
import com.rokid.context.core.model.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class HudConnection { CONNECTING, LIVE, OFFLINE, OUTDATED }

/** Pure: what the HUD shows about the phone link (spec §3.6; adapted from Live Fit `connectionFor`). */
fun connectionFor(everReceived: Boolean, online: Boolean, outdated: Boolean, sinceStartMs: Long): HudConnection = when {
    outdated -> HudConnection.OUTDATED
    online -> HudConnection.LIVE
    everReceived || sinceStartMs >= 12_000 -> HudConnection.OFFLINE
    else -> HudConnection.CONNECTING
}

/**
 * Glasses side of the CXR link (spec §3.1): renders phone frames and sends commands, `pc_hello` and frame chunks.
 * Session-open is tracked locally (spec §1.7): set on the first phone message of a session (and then `pc_hello` goes
 * out), cleared on a bridge disconnect, a silent phone, a version mismatch or when the activity exits. Frames go out
 * only while it is open; a send result of 0 never means delivered, only `pc_frame_ack` does.
 */
class HudController(
    private val scope: CoroutineScope,
    private val bridge: GlassesBridge,
    private val cache: HudSettingsCache,
    private val hello: () -> Hello,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val liveness = LivenessMonitor(clock)
    private val startedAt = clock()
    @Volatile private var everReceived = false
    @Volatile private var outdated = false

    private val _frame = MutableStateFlow<StateFrame?>(null)
    val frame: StateFlow<StateFrame?> = _frame
    private val _settings = MutableStateFlow(cache.read())
    val settings: StateFlow<HudSettings> = _settings
    private val _connection = MutableStateFlow(HudConnection.CONNECTING)
    val connection: StateFlow<HudConnection> = _connection
    private val _sessionOpen = MutableStateFlow(false)
    val sessionOpen: StateFlow<Boolean> = _sessionOpen

    /** All three are called on a bridge thread. */
    @Volatile var onFrameAck: (FrameAck) -> Unit = {}
    @Volatile var onSessionOpened: () -> Unit = {}
    @Volatile var onPhonePauses: (List<PauseRecord>) -> Unit = {}

    fun start() {
        bridge.subscribe(Channels.STATE) { onState(it) }
        bridge.subscribe(Channels.SETTINGS) { onSettings(it) }
        bridge.subscribe(Channels.FRAME_ACK) { onAck(it) }
        bridge.subscribe(Channels.HELLO_REQUEST) { onHelloRequest(it) }
        bridge.setConnectionListener(connected = { Log.i(TAG, "phone connected") }, disconnected = { closeSession("bt disconnected") })
        scope.launch { while (isActive) { refreshConnection(); delay(1_000) } }
    }

    private fun onState(text: String) {
        when (val d = Wire.decode<StateFrame>(text)) {
            is Decoded.Ok -> {
                phoneMessage()
                _frame.value = d.value
                (d.value.capture as? CaptureState.Paused)?.let { onPhonePauses(it.records) }
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> Log.w(TAG, "bad pc_state: ${d.reason}")
        }
        refreshConnection()
    }

    private fun onSettings(text: String) {
        when (val d = Wire.decode<HudSettings>(text)) {
            is Decoded.Ok -> {
                phoneMessage()
                _settings.value = d.value
                cache.write(d.value)
                onPhonePauses(d.value.pauses)
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> Log.w(TAG, "bad pc_settings: ${d.reason}")
        }
        refreshConnection()
    }

    /** The phone holds a frame on a stale clock offset and asks for a fresh `pc_hello` (same path as the session hello). */
    private fun onHelloRequest(text: String) {
        when (val d = Wire.decode<HelloRequest>(text)) {
            is Decoded.Ok -> {
                val wasOpen = _sessionOpen.value
                phoneMessage() // opens the session (and says hello) if this is its first message
                if (wasOpen) sendHello()
            }
            is Decoded.VersionMismatch -> markOutdated(d.remote)
            is Decoded.Malformed -> Log.w(TAG, "bad pc_hello_request: ${d.reason}")
        }
        refreshConnection()
    }

    private fun onAck(text: String) { (Wire.decode<FrameAck>(text) as? Decoded.Ok)?.value?.let { onFrameAck(it) } }

    /** First message of a session: say hello (clock, battery, buffer, pauses) before any frame is sent. */
    private fun phoneMessage() {
        everReceived = true
        outdated = false
        liveness.onFrame()
        if (_sessionOpen.compareAndSet(false, true)) {
            Log.i(TAG, "session open")
            sendHello()
            onSessionOpened()
        }
    }

    private fun markOutdated(remote: Int?) {
        Log.w(TAG, "phone protocol v=$remote, glasses v=$PROTOCOL_VERSION")
        outdated = true
        closeSession("version mismatch")
    }

    private fun closeSession(why: String) {
        liveness.onTransportDisconnected()
        if (_sessionOpen.compareAndSet(true, false)) Log.i(TAG, "session closed: $why")
        refreshConnection()
    }

    /** The activity is going away: the phone's CUSTOM_APP session closes with it (spec §1.7). */
    fun onActivityExit() = closeSession("activity exit")

    fun send(command: Command): Boolean = bridge.send(Channels.CMD, Wire.encode(CommandEnvelope(command = command))) == 0

    fun sendHello(): Boolean = bridge.send(Channels.HELLO, Wire.encode(hello())) == 0

    /** -1 while the session is closed; otherwise CXR's code (0 = handed over, not delivered). */
    fun sendFrameChunk(chunk: FrameChunk): Int =
        if (!_sessionOpen.value) -1 else bridge.send(Channels.FRAME, Wire.encode(chunk.meta), chunk.chunk)

    private fun refreshConnection() {
        if (_sessionOpen.value && !liveness.isOnline()) { closeSession("phone silent"); return }
        _connection.value = connectionFor(everReceived, liveness.isOnline(), outdated, clock() - startedAt)
    }

    companion object { const val TAG = "PcHud" }
}
