package com.rokid.context.voice

import com.rokid.context.core.model.audio.Pcm
import kotlinx.coroutines.delay
import kotlin.math.min

/**
 * The external-audio probe's feed (spec §5.1.1), pure so its timing is unit-tested: the bundled clip plus
 * [ExternalAudioProbe.SILENCE_TAIL_BYTES] of silence, offered in 100 ms chunks at real-time pace, then [PcmFeeder.finish]
 * and a pump every [PUMP_MS] until the write end is closed. The drain deadline is only checked inside `pump`, so the
 * pumping goes on past it (Task 5 carry): a recognizer that stopped reading still gets EOF.
 */
object ProbeFeed {
    const val DRAIN_MS = 500L
    const val PUMP_MS = 20L

    /** Whether a probe of [clipBytes] through a pipe of measured [capacityBytes] can decide anything; if not, don't run it. */
    fun canDecide(clipBytes: Int, capacityBytes: Long): Boolean =
        ExternalAudioProbe.decidable((clipBytes + ExternalAudioProbe.SILENCE_TAIL_BYTES).toLong(), clipBytes.toLong(), capacityBytes)

    /** Streams [clip] + silence into [feeder] and returns the run's bytes. [pause] waits that many ms (a coroutine delay). */
    suspend fun stream(feeder: PcmFeeder, clip: ByteArray, pause: suspend (Long) -> Unit = { delay(it) }): ProbeOutcome {
        val feed = clip + ByteArray(ExternalAudioProbe.SILENCE_TAIL_BYTES)
        var i = 0
        while (i < feed.size && !feeder.closed) {
            val n = min(Pcm.CHUNK_BYTES, feed.size - i)
            feeder.offer(feed.copyOfRange(i, i + n))
            i += n
            pause(Pcm.millis(n.toLong())) // real-time pace, as the glasses send it
        }
        feeder.finish(drainMs = DRAIN_MS)
        var pumps = 0
        while (!feeder.closed && pumps++ < MAX_DRAIN_PUMPS) {
            pause(PUMP_MS)
            feeder.pump()
        }
        feeder.close() // a wall clock that stepped back can't keep the pipe open
        val m = feeder.meter()
        return ProbeOutcome(m.fedBytes, m.acceptedBytes, m.capacityBytes, m.peerClosed, clipBytes = clip.size.toLong())
    }

    /**
     * The run's bytes count only when the recognizer ended normally ([SttResult.Text], [SttResult.NoSpeech], or no final
     * before the wait ran out). A recognizer that errored (busy, disconnected, pack missing, no permission) stopped
     * reading for its own reasons: null, NOT_VERIFIED, so a transient error is never stored as UNSUPPORTED (review I1).
     */
    fun judge(final: SttResult?, outcome: ProbeOutcome): ProbeOutcome? = when (final) {
        null, is SttResult.Text, SttResult.NoSpeech -> outcome
        SttResult.Failed, SttResult.PackMissing, SttResult.NoPermission, SttResult.FallbackSuspected -> null
    }

    private const val MAX_DRAIN_PUMPS = (4 * DRAIN_MS / PUMP_MS).toInt()
}
