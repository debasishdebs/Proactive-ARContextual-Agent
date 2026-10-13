package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm

enum class ProbeVerdict { SUPPORTED, UNSUPPORTED }

/** One probe run: bytes fed at real-time pace, bytes the pipe accepted, and whether the recognizer said anything. */
data class ProbeOutcome(val fedBytes: Long, val acceptedBytes: Long, val gotOutput: Boolean)

/** Spec §5.1.1 external-audio verification (onboarding, after OS/app updates, after the guard fired). */
object ExternalAudioProbe {
    /** 134 142 bytes of "What do I need from the store" (phase 0's clip), bundled in `:services:voice` assets. */
    const val ASSET = "voice_probe.pcm"
    /** A paced feed of at least 96 KB (≥ 3 s), above the 64 KiB pipe capacity. */
    const val MIN_FEED_BYTES = 3L * Pcm.BYTES_PER_SECOND
    /** 1 s of silence after the clip, so a recognizer that stops at the end of speech still reads past the capacity. */
    const val SILENCE_TAIL_BYTES = Pcm.BYTES_PER_SECOND

    /** SUPPORTED when at least half the feed was certainly consumed; null when the feed was too short to decide. */
    fun verdict(o: ProbeOutcome): ProbeVerdict? {
        if (o.fedBytes < MIN_FEED_BYTES) return null
        val consumedAtLeast = (o.acceptedBytes - PipeLimits.PIPE_CAPACITY_BYTES).coerceAtLeast(0)
        return if (consumedAtLeast * 2 >= o.fedBytes) ProbeVerdict.SUPPORTED else ProbeVerdict.UNSUPPORTED
    }
}
