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
    val capacityBytes: Long,
    val peerClosed: Boolean = false,
    /** The speech part of the feed (the bundled clip); by default everything before the silence tail. */
    val clipBytes: Long = fedBytes - ExternalAudioProbe.SILENCE_TAIL_BYTES,
)

/** Spec §5.1.1 external-audio verification (onboarding, after OS/app updates, after the guard fired). */
object ExternalAudioProbe {
    /** 134 142 bytes of "What do I need from the store" (phase 0's clip), bundled in `:services:voice` assets. */
    const val ASSET = "voice_probe.pcm"
    /** A paced feed of at least 96 KB (≥ 3 s), above the pinned pipe's measured capacity. */
    const val MIN_FEED_BYTES = 3L * Pcm.BYTES_PER_SECOND
    /** 1 s of silence after the clip, so a recognizer that stops at the end of speech still reads past the capacity. */
    const val SILENCE_TAIL_BYTES = Pcm.BYTES_PER_SECOND

    /**
     * SUPPORTED when at least half the feed was certainly consumed (accepted − capacity ≥ fed / 2). Null (NOT_VERIFIED)
     * whenever the run can't decide: a feed too short, or a pipe too big to tell a reader from a non-reader
     * (2 × capacity > fed, or capacity > clip). A recognizer that closed our pipe before the feed ended is UNSUPPORTED.
     */
    fun verdict(o: ProbeOutcome): ProbeVerdict? {
        if (o.fedBytes < MIN_FEED_BYTES) return if (o.peerClosed) ProbeVerdict.UNSUPPORTED else null
        if (!decidable(o.fedBytes, o.clipBytes, o.capacityBytes)) return null
        val consumedAtLeast = (o.acceptedBytes - o.capacityBytes).coerceAtLeast(0)
        return if (consumedAtLeast * 2 >= o.fedBytes) ProbeVerdict.SUPPORTED else ProbeVerdict.UNSUPPORTED
    }

    /** Whether a feed of [fedBytes] (speech: [clipBytes]) through a pipe of [capacityBytes] can produce a verdict at all. */
    fun decidable(fedBytes: Long, clipBytes: Long, capacityBytes: Long): Boolean =
        2 * capacityBytes <= fedBytes && capacityBytes <= clipBytes
}
