package com.rokid.context.voice.android

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.rokid.context.core.log.PcLog
import com.rokid.context.voice.PipeCapacity
import com.rokid.context.voice.PipeSink
import java.io.FileDescriptor
import java.io.IOException

/**
 * The write end of a `createPipe()` pair in non-blocking mode (spec §5.1.1: a non-reading recognizer can't hang the
 * feeder), pinned to 64 KiB with `F_SETPIPE_SZ` (Task 5 ruling: the kernel default of 16 page slots is 256 000 bytes of
 * 3 200-byte writes on 16 KiB-page phones, too big for the probe to decide). A refused pin throws (fail closed: the
 * guard and the probe must never assume a smaller pipe than the real one); [pipeSize] is what the kernel then reports.
 * Writes are ≤ 3 200 bytes (< PIPE_BUF), so each is all-or-nothing: EAGAIN = full, EPIPE = the reader went away.
 * `Os.write` only: `FileOutputStream` loops and throws.
 */
class AndroidPipeSink internal constructor(private val write: ParcelFileDescriptor, private val posix: Posix) : PipeSink {
    constructor(write: ParcelFileDescriptor) : this(write, Posix.SYSTEM)

    /** The `android.system.Os` calls and constants this sink uses (the JVM test runtime stubs them, so tests pass their own). */
    @Suppress("PropertyName")
    internal interface Posix {
        val F_GETFL: Int
        val F_SETFL: Int
        val O_NONBLOCK: Int
        val EAGAIN: Int
        fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int): Int
        fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int

        companion object {
            val SYSTEM = object : Posix {
                override val F_GETFL get() = OsConstants.F_GETFL
                override val F_SETFL get() = OsConstants.F_SETFL
                override val O_NONBLOCK get() = OsConstants.O_NONBLOCK
                override val EAGAIN get() = OsConstants.EAGAIN
                override fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int) = Os.fcntlInt(fd, cmd, arg)
                override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int) = Os.write(fd, bytes, offset, count)
            }
        }
    }

    /** The pipe's size in bytes after the pin (`F_GETPIPE_SZ`). */
    val pipeSize: Int

    init {
        val fd = write.fileDescriptor
        try {
            posix.fcntlInt(fd, F_SETPIPE_SZ, PINNED_PIPE_BYTES)
            pipeSize = posix.fcntlInt(fd, F_GETPIPE_SZ, 0)
        } catch (e: ErrnoException) {
            PcLog.w(TAG, "pipe size not pinned (errno ${e.errno})")
            throw IOException("pipe size not pinned (errno ${e.errno})")
        }
        posix.fcntlInt(fd, posix.F_SETFL, posix.fcntlInt(fd, posix.F_GETFL, 0) or posix.O_NONBLOCK)
    }

    override fun tryWrite(bytes: ByteArray): Int = try {
        // A short write leaves part of a chunk in the pipe; re-sending it whole would shift the PCM16 stream: end the feed.
        if (posix.write(write.fileDescriptor, bytes, 0, bytes.size) == bytes.size) PipeSink.WROTE else PipeSink.CLOSED
    } catch (e: ErrnoException) {
        if (e.errno == posix.EAGAIN) PipeSink.FULL else PipeSink.CLOSED
    } catch (e: IOException) {
        PipeSink.CLOSED
    }

    override fun close() { runCatching { write.close() } }

    companion object {
        /** `F_SETPIPE_SZ` / `F_GETPIPE_SZ` (Linux `fcntl.h`; not in `OsConstants`). */
        const val F_SETPIPE_SZ = 1031
        const val F_GETPIPE_SZ = 1032
        const val PINNED_PIPE_BYTES = 65_536
        internal const val TAG = "PcVoice"
    }
}

/**
 * Recognizer audio pipes, and the capacity a pipe built that way really has. The capacity is measured once per
 * process on a pinned scratch pipe; every feed pipe is then checked against it: one whose pin was refused, or whose
 * size is above the measured pipe's, fails closed (review I2: a bigger real pipe would make the guard and the probe
 * over-count what the recognizer consumed). Non-blocking syscalls on kernel memory, no file I/O: any thread may call.
 */
internal class AndroidPipes(
    private val posix: AndroidPipeSink.Posix = AndroidPipeSink.Posix.SYSTEM,
    private val create: () -> Array<ParcelFileDescriptor> = ParcelFileDescriptor::createPipe,
) {
    class Feed(val read: ParcelFileDescriptor, val sink: AndroidPipeSink, val capacityBytes: Long)
    private class Measured(val capacityBytes: Long, val pipeSize: Int)

    @Volatile private var measured: Measured? = null

    fun capacity(): Long = measure().capacityBytes

    /** A feed pipe: the read end for `EXTRA_AUDIO_SOURCE`, the pinned sink, and the capacity to judge it by. */
    fun openFeed(): Feed {
        val m = measure()
        val (read, sink) = open()
        if (sink.pipeSize > m.pipeSize) {
            sink.close(); runCatching { read.close() }
            PcLog.w(TAG, "feed pipe size=${sink.pipeSize} above measured size=${m.pipeSize}")
            throw IOException("feed pipe bigger than the measured one")
        }
        return Feed(read, sink, m.capacityBytes)
    }

    private fun measure(): Measured = measured ?: synchronized(this) {
        measured ?: run {
            val (read, sink) = open() // a refused pin throws here: nothing is measured or cached
            try { Measured(PipeCapacity.measure(sink), sink.pipeSize) } finally { sink.close(); runCatching { read.close() } }
        }.also {
            // A scratch pipe that took nothing isn't remembered: callers fail closed and the next call measures again.
            if (it.capacityBytes <= 0) throw IOException("scratch pipe took nothing")
            measured = it
            PcLog.i(TAG, "pipe capacity=${it.capacityBytes} size=${it.pipeSize}")
        }
    }

    private fun open(): Pair<ParcelFileDescriptor, AndroidPipeSink> {
        val (read, write) = create()
        return try {
            read to AndroidPipeSink(write, posix)
        } catch (e: Exception) {
            runCatching { read.close() }; runCatching { write.close() }
            throw e
        }
    }

    companion object {
        private const val TAG = AndroidPipeSink.TAG
        /** The process's pipes (production). */
        val SHARED = AndroidPipes()
    }
}
