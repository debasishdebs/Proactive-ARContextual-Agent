package com.rokid.context.link

import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.TraceChunk

/** Glasses → phone messages that passed the protocol-version gate (spec §1.5). */
sealed interface GlassesEvent {
    data class HelloReceived(val hello: Hello) : GlassesEvent
    data class Cmd(val command: Command) : GlassesEvent
    data class Frame(val chunk: FrameChunk) : GlassesEvent
    /** PCM 16 kHz mono (consumed in Phase 6). */
    class Audio(val pcm: ByteArray) : GlassesEvent
    data object ListenEnd : GlassesEvent
    /** Fix wave H: the wearer exited the glasses app on purpose (`pc_user_exit`). */
    data object UserExit : GlassesEvent
    /** `pc_motion_hint`: the glasses' motion state (feeds the phone context hub). */
    data class MotionHintReceived(val hint: MotionHint) : GlassesEvent
    /** `pc_capture_plan_ack` (slice 3a): not yet correlated, see [PlanAckGate]. */
    data class PlanAckReceived(val ack: CapturePlanAck) : GlassesEvent
    /** `pc_mark` (slice 3a). Accepted whatever its age: `atMs` is the occurrence time, not a freshness stamp. */
    data class MarkReceived(val mark: MarkMessage) : GlassesEvent
    /** `pc_trace_chunk` (slice 3a). */
    data class TraceChunkReceived(val chunk: TraceChunk) : GlassesEvent
    /** The glasses app speaks another protocol version ([remote] = its `v`, null when absent). */
    data class Outdated(val remote: Int?) : GlassesEvent
}
