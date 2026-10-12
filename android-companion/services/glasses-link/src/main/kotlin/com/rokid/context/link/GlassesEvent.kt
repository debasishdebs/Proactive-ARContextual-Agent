package com.rokid.context.link

import com.rokid.context.core.model.Command
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.Hello

/** Glasses → phone messages that passed the protocol-version gate (spec §1.5). */
sealed interface GlassesEvent {
    data class HelloReceived(val hello: Hello) : GlassesEvent
    data class Cmd(val command: Command) : GlassesEvent
    data class Frame(val chunk: FrameChunk) : GlassesEvent
    /** PCM 16 kHz mono (consumed in Phase 6). */
    class Audio(val pcm: ByteArray) : GlassesEvent
    data object ListenEnd : GlassesEvent
    /** The glasses app speaks another protocol version ([remote] = its `v`, null when absent). */
    data class Outdated(val remote: Int?) : GlassesEvent
}
