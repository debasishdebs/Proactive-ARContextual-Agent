package com.rokid.context.voice.android

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.rokid.context.voice.PipeCapacity
import com.rokid.context.voice.PipeSink
import java.io.FileDescriptor

/**
 * The write end of a `createPipe()` pair in non-blocking mode (spec §5.1.1: a non-reading recognizer can't hang the
 * feeder), pinned to 64 KiB with `F_SETPIPE_SZ` (Task 5 ruling: the kernel default of 16 page slots is 256 000 bytes of
 * 3 200-byte writes on 16 KiB-page phones, too big for the probe to decide). Writes are ≤ 3 200 bytes (< PIPE_BUF), so
 * each is all-or-nothing: EAGAIN = full, EPIPE = the reader went away. `Os.write` only: `FileOutputStream` loops and throws.
 */
class AndroidPipeSink internal constructor(private val write: ParcelFileDescriptor, private val posix: Posix) : PipeSink {
    constructor(write: ParcelFileDescriptor) : this(write, Posix.SYSTEM)

    /** The two `android.system.Os` calls this sink makes (the JVM test runtime stubs them, so tests pass their own). */
    internal interface Posix {
        fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int): Int
        fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int

        companion object {
            val SYSTEM = object : Posix {
                override fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int) = Os.fcntlInt(fd, cmd, arg)
                override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int) = Os.write(fd, bytes, offset, count)
            }
        }
    }

    init {
        val fd = write.fileDescriptor
        // Best effort: a kernel that refuses the size keeps its own, and the measured capacity tells the truth either way.
        try { posix.fcntlInt(fd, F_SETPIPE_SZ, PINNED_PIPE_BYTES) } catch (e: ErrnoException) { Log.w(TAG, "pipe size not pinned (errno ${e.errno})") }
        posix.fcntlInt(fd, OsConstants.F_SETFL, posix.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
    }

    override fun tryWrite(bytes: ByteArray): Int = try {
        if (posix.write(write.fileDescriptor, bytes, 0, bytes.size) == bytes.size) PipeSink.WROTE else PipeSink.FULL
    } catch (e: ErrnoException) {
        if (e.errno == OsConstants.EAGAIN) PipeSink.FULL else PipeSink.CLOSED
    } catch (e: java.io.IOException) {
        PipeSink.CLOSED
    }

    override fun close() { runCatching { write.close() } }

    companion object {
        /** `F_SETPIPE_SZ` (Linux `fcntl.h`; not in `OsConstants`). */
        const val F_SETPIPE_SZ = 1031
        const val PINNED_PIPE_BYTES = 65_536
        private const val TAG = "PcVoice"
    }
}

/** Recognizer audio pipes, and the capacity a pipe built that way really has. */
internal object AndroidPipes {
    /** A recognizer audio pipe: the read end for `EXTRA_AUDIO_SOURCE`, the write end as a pinned non-blocking sink. */
    fun open(): Pair<ParcelFileDescriptor, AndroidPipeSink> {
        val (read, write) = ParcelFileDescriptor.createPipe()
        return try {
            read to AndroidPipeSink(write)
        } catch (e: Exception) {
            runCatching { read.close() }; runCatching { write.close() }
            throw e
        }
    }

    @Volatile private var measured: Long? = null

    /**
     * [PipeCapacity.measure] on a scratch pipe made exactly like the feed pipe ([AndroidPipeSink]: pinned, O_NONBLOCK),
     * once per process: the page size and the pipe limits don't change while it runs. Non-blocking writes into kernel
     * memory, no file I/O, so any thread may call it.
     */
    fun capacity(): Long = measured ?: synchronized(this) {
        measured ?: run {
            val (read, sink) = open()
            try { PipeCapacity.measure(sink) } finally { sink.close(); runCatching { read.close() } }
        }.also {
            check(it > 0) { "scratch pipe took nothing" } // not remembered: callers fail closed and the next call measures again
            measured = it
            Log.i("PcVoice", "pipe capacity=$it")
        }
    }
}
