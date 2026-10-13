package com.rokid.context.core.model.audio

/** `pc_audio` format (spec §1.5, phase 0): PCM 16-bit little-endian, mono, 16 kHz, in 3 200-byte (100 ms) chunks. */
object Pcm {
    const val SAMPLE_RATE = 16_000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val CHUNK_BYTES = 3_200

    /** Milliseconds of audio in [bytes]. */
    fun millis(bytes: Long): Long = bytes * 1_000 / BYTES_PER_SECOND
}
