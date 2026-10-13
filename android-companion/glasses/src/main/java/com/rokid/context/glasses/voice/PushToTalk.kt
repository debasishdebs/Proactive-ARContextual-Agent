package com.rokid.context.glasses.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.rokid.context.core.model.audio.EnergyVad
import com.rokid.context.core.model.audio.Pcm
import com.rokid.context.core.model.audio.VadDecision
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Base64
import kotlin.concurrent.thread

/** Blocking PCM16 mono 16 kHz source; [read] returns bytes read, <= 0 at the end or on an error. */
interface PcmSource {
    fun start()
    fun read(buf: ByteArray): Int
    fun close()
}

class AudioRecordSource : PcmSource {
    @SuppressLint("MissingPermission") // PushToTalk checks RECORD_AUDIO before it opens a source
    private val rec: AudioRecord = run {
        val min = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioRecord(MediaRecorder.AudioSource.MIC, Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Pcm.CHUNK_BYTES * 2))
    }.also { if (it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw IllegalStateException("AudioRecord not initialized") } }

    override fun start() = rec.startRecording()
    override fun read(buf: ByteArray) = rec.read(buf, 0, buf.size)
    override fun close() { runCatching { rec.stop() }; rec.release() }
}

/** How push-to-talk reaches the phone: `ListenStart` on pc_cmd, Base64 chunks on pc_audio, then pc_listen_end. */
interface PttLink {
    fun start(): Boolean
    fun audio(base64: String): Boolean
    fun end()
}

enum class PttProblem { NO_PERMISSION, MIC_UNAVAILABLE, LINK_DOWN }

/**
 * Tap-to-talk on the glasses (spec §5.1), adapted from Live Fit `PushToTalk`: the glasses' own mic, 100 ms chunks of
 * 3 200 bytes (phase 0), ending on a second tap ([stop]), 1.5 s of silence, 5 s without speech or 15 s ([EnergyVad]).
 * [cancel] (double-tap) stops without `pc_listen_end`: the phone already has the Cancel. [recording] is the runtime's
 * `listening` flag, so frames never flush while it is true (spec §1.8). Audio is never logged.
 */
class PushToTalk(
    private val link: PttLink,
    val recording: MutableStateFlow<Boolean> = MutableStateFlow(false),
    private val hasPermission: () -> Boolean = { true },
    private val sourceFactory: () -> PcmSource = { AudioRecordSource() },
    private val vad: () -> EnergyVad = { EnergyVad() },
    private val log: (String) -> Unit = {},
) {
    /** Called on the recording thread. */
    @Volatile var onProblem: (PttProblem) -> Unit = {}
    private val lock = Any()
    private var running = false
    private var stopRequested = false
    private var cancelled = false

    /** False when nothing started: no permission, or a capture is still running. */
    fun start(): Boolean {
        if (!hasPermission()) { onProblem(PttProblem.NO_PERMISSION); return false }
        synchronized(lock) {
            if (running) return false
            running = true
            stopRequested = false
            cancelled = false
            recording.value = true
        }
        thread(name = "ptt", isDaemon = true) {
            try { capture() } finally { synchronized(lock) { running = false; recording.value = false } }
        }
        return true
    }

    fun stop() = synchronized(lock) { if (running) stopRequested = true }

    fun cancel() = synchronized(lock) { if (running) { stopRequested = true; cancelled = true } }

    private fun stopping() = synchronized(lock) { stopRequested }

    private fun capture() {
        var opened: PcmSource? = null
        val source = try {
            sourceFactory().also { opened = it; it.start() }
        } catch (e: Exception) {
            opened?.let { runCatching { it.close() } } // built but failed to start: it still holds the mic
            log("mic failed (${e.javaClass.simpleName})"); onProblem(PttProblem.MIC_UNAVAILABLE); return
        }
        var started = false
        try {
            if (stopping()) return
            if (!link.start()) { onProblem(PttProblem.LINK_DOWN); return }
            started = true
            log("listening")
            val v = vad()
            val chunk = ByteArray(Pcm.CHUNK_BYTES)
            while (!stopping()) {
                val n = source.read(chunk)
                if (n <= 0 || stopping()) break
                val bytes = chunk.copyOf(n)
                if (!link.audio(Base64.getEncoder().encodeToString(bytes))) { onProblem(PttProblem.LINK_DOWN); break }
                if (v.feed(bytes) != VadDecision.CONTINUE) break
            }
        } catch (e: Exception) {
            log("capture failed (${e.javaClass.simpleName})"); onProblem(PttProblem.MIC_UNAVAILABLE)
        } finally {
            runCatching { source.close() }
            if (started && !synchronized(lock) { cancelled }) runCatching { link.end() }
            log("listen ended")
        }
    }
}
