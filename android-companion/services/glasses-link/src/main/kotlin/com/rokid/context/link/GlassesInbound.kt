package com.rokid.context.link

import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.CommandEnvelope
import com.rokid.context.core.model.Decoded
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameMeta
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Wire
import java.util.Base64

/**
 * Pure decoding and version gate for glasses → phone messages (ported from Live Fit `GlassesInbound`). [parts] are a
 * message's Caps strings in order: one JSON string, except `pc_frame` (meta JSON + Base64 chunk). A message from another
 * protocol version becomes [GlassesEvent.Outdated] and is never applied. `pc_audio` is raw Base64 PCM with no `v`;
 * `pc_listen_end` carries `{"v":N}` (preflight F3) but its body is ignored. Both are bound to the session's version by
 * the `ListenStart` they follow: they count only after an accepted (version-gated) `ListenStart`.
 */
class GlassesInbound {
    private var listenAccepted = false

    /** A new CXR session: nothing from the previous one is in flight. */
    @Synchronized fun reset() { listenAccepted = false }

    @Synchronized fun onMessage(channel: String, parts: List<String>): List<GlassesEvent> {
        val first = parts.firstOrNull() ?: return emptyList()
        return when (channel) {
            Channels.HELLO -> gate(Wire.decode<Hello>(first)) { GlassesEvent.HelloReceived(it) }
            Channels.CMD -> gate(Wire.decode<CommandEnvelope>(first)) { env ->
                when (env.command) {
                    Command.ListenStart -> listenAccepted = true
                    Command.Cancel -> listenAccepted = false
                    else -> Unit
                }
                GlassesEvent.Cmd(env.command)
            }
            Channels.FRAME -> if (parts.size < 2) emptyList() else gate(Wire.decode<FrameMeta>(first)) { GlassesEvent.Frame(FrameChunk(it, parts[1])) }
            Channels.AUDIO -> if (!listenAccepted) emptyList() else listOfNotNull(
                runCatching { Base64.getDecoder().decode(first) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { GlassesEvent.Audio(it) },
            )
            Channels.USER_EXIT -> gate(Wire.decode<com.rokid.context.core.model.UserExit>(first)) { GlassesEvent.UserExit }
            Channels.MOTION_HINT -> gate(Wire.decode<com.rokid.context.core.model.MotionHint>(first)) { GlassesEvent.MotionHintReceived(it) }
            Channels.CAPTURE_PLAN_ACK -> gate(Wire.decode<com.rokid.context.core.model.CapturePlanAck>(first)) { GlassesEvent.PlanAckReceived(it) }
            // Only `v` gates a mark: a queued mark older than the session is still a real moment (S74).
            Channels.MARK -> gate(Wire.decode<com.rokid.context.core.model.MarkMessage>(first)) { GlassesEvent.MarkReceived(it) }
            Channels.TRACE_CHUNK -> gate(Wire.decode<com.rokid.context.core.model.TraceChunk>(first)) { GlassesEvent.TraceChunkReceived(it) }
            Channels.LISTEN_END -> if (!listenAccepted) emptyList() else { listenAccepted = false; listOf(GlassesEvent.ListenEnd) }
            else -> emptyList()
        }
    }

    private inline fun <T> gate(d: Decoded<T>, ok: (T) -> GlassesEvent): List<GlassesEvent> = when (d) {
        is Decoded.Ok -> listOf(ok(d.value))
        is Decoded.VersionMismatch -> listOf(GlassesEvent.Outdated(d.remote))
        is Decoded.Malformed -> emptyList()
    }
}
