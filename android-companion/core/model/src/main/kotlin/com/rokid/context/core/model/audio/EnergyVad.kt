package com.rokid.context.core.model.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class VadDecision { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_REACHED }

/**
 * Adaptive energy VAD (ported from Live Fit `services/voice/EnergyVad`, retuned for BUG-R3-3). A chunk is speech when
 * its RMS is at least `max(minSpeechRms, speechFactor x floor)`. The floor is the median chunk RMS of the first
 * [floorWindowMs], capped at [maxFloorRms], and afterwards only ever lowered by a quieter chunk (a pause between words
 * shows the true floor; speech never raises it). When the floor drops, the chunks already fed are judged again, so
 * soft speech said right at the tap still counts.
 *
 * Ends [silenceMs] after the last speech, when nothing was said for [noSpeechMs] (there is no auto-end before any
 * speech), or at [maxMs].
 *
 * Values (PCM16, full scale 32 767; dBFS = 20 log10(rms / 32 767)):
 * - [minSpeechRms] 80 (-52 dBFS): above a MEMS mic's quiet-room self-noise (about -60..-65 dBFS, RMS 18-33) and
 *   11.5 dB under the old fixed 300 (-41 dBFS) that soft speech and a speaker across a desk never reached.
 * - [speechFactor] 3 (+9.5 dB over the floor): speech is typically 10-20 dB over ambient noise; RMS of a steady noise
 *   over a 1 600-sample chunk varies only a few percent, so noise alone does not reach 3x.
 * - [maxFloorRms] 400 (-38 dBFS): if the wearer speaks through the whole floor window the threshold stays at most
 *   1 200 (-29 dBFS), still under close speech at the glasses mic.
 * - [silenceMs] 2 200: within-turn hesitation pauses run up to about 2 s ("what's on my ... shopping list"); 1.5 s cut
 *   asks after one or two words. The tap still sends at once.
 *
 * Lives in `:core:model` because the glasses may depend only on `:core:*` (ruling); plain JVM.
 */
class EnergyVad(
    private val silenceMs: Int = 2_200,
    private val noSpeechMs: Int = 5_000,
    private val maxMs: Int = 15_000,
    private val minSpeechRms: Double = 80.0,
    private val speechFactor: Double = 3.0,
    private val floorWindowMs: Int = 300,
    private val maxFloorRms: Double = 400.0,
) {
    /** RMS and length of every chunk of this listen (at most [maxMs] / chunk, 150 for 100 ms chunks). */
    private val rms = ArrayList<Double>()
    private val lengthMs = ArrayList<Double>()
    private var totalMs = 0.0
    private var floor: Double? = null
    private var heardSpeech = false
    private var silentMs = 0.0

    fun reset() {
        rms.clear(); lengthMs.clear()
        totalMs = 0.0; floor = null; heardSpeech = false; silentMs = 0.0
    }

    fun feed(pcm16le: ByteArray): VadDecision {
        val samples = pcm16le.size / 2
        if (samples == 0) return VadDecision.CONTINUE
        val buf = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        repeat(samples) { val s = buf.short.toDouble(); sum += s * s }
        val r = sqrt(sum / samples)
        val ms = samples * 1_000.0 / Pcm.SAMPLE_RATE
        totalMs += ms
        rms += r; lengthMs += ms
        val f = floor
        when {
            f == null && totalMs >= floorWindowMs -> { floor = min(median(rms), maxFloorRms); judgeAll() }
            f == null -> Unit // still estimating the floor: nothing is speech yet
            r < f -> { floor = r; judgeAll() }
            r >= threshold() -> { heardSpeech = true; silentMs = 0.0 }
            heardSpeech -> silentMs += ms
        }
        return when {
            totalMs >= maxMs -> VadDecision.MAX_REACHED
            heardSpeech && silentMs >= silenceMs -> VadDecision.END_OF_SPEECH
            !heardSpeech && totalMs >= noSpeechMs -> VadDecision.NO_SPEECH
            else -> VadDecision.CONTINUE
        }
    }

    private fun threshold() = max(minSpeechRms, speechFactor * (floor ?: 0.0))

    /** The floor was set or lowered: the last chunk at or over the new threshold is where the silence starts. */
    private fun judgeAll() {
        val t = threshold()
        val last = rms.indexOfLast { it >= t }
        if (last < 0) return // the threshold only falls, so speech heard before is still found here
        heardSpeech = true
        silentMs = (last + 1 until rms.size).sumOf { lengthMs[it] }
    }

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]
}
