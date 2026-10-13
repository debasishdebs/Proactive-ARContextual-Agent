package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm

enum class ProbeVerdict { SUPPORTED, UNSUPPORTED }

/**
 * One probe run: bytes fed at real-time pace, bytes the pipe accepted, the pipe's measured capacity, and whether the
 * recognizer closed its end early. The verdict is byte-only: text proves nothing about which audio was heard.
 */
data class ProbeOutcome(
    val fedBytes: Long,
    val acceptedBytes: Long,
    val capacityBytes: Long = PipeLimits.PIPE_CAPACITY_BYTES,
    val peerClosed: Boolean = false,
)

/** Spec §5.1.1 external-audio verification (onboarding, after OS/app updates, after the guard fired). */
object ExternalAudioProbe {
    /** 134 142 bytes of "What do I need from the store" (phase 0's clip), bundled in `:services:voice` assets. */
    const val ASSET = "voice_probe.pcm"
    /** A paced feed of at least 96 KB (≥ 3 s), above the 64 KiB pipe capacity. */
    const val MIN_FEED_BYTES = 3L * Pcm.BYTES_PER_SECOND
    /** 1 s of silence after the clip, so a recognizer that stops at the end of speech still reads past the capacity. */
    const val SILENCE_TAIL_BYTES = Pcm.BYTES_PER_SECOND

    /**
     * SUPPORTED when at least half the feed was certainly consumed (accepted − capacity ≥ fed / 2). A feed too short
     * to decide gives null, unless the recognizer closed our pipe first: that is UNSUPPORTED.
     */
    fun verdict(o: ProbeOutcome): ProbeVerdict? {
        if (o.fedBytes < MIN_FEED_BYTES) return if (o.peerClosed) ProbeVerdict.UNSUPPORTED else null
        val consumedAtLeast = (o.acceptedBytes - o.capacityBytes).coerceAtLeast(0)
        return if (consumedAtLeast * 2 >= o.fedBytes) ProbeVerdict.SUPPORTED else ProbeVerdict.UNSUPPORTED
    }
}
