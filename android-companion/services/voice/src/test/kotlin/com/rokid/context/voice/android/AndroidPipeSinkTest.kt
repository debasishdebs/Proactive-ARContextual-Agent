package com.rokid.context.voice.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.voice.ExternalAudioProbe
import com.rokid.context.voice.PipeCapacity
import com.rokid.context.voice.PipeSink
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.io.IOException
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Robolectric stubs `android.system.Os` (writes return 0; F_GETFL = F_SETFL = O_NONBLOCK = 0), so the real pipe is
 * PENDING-DEVICE. A fake [AndroidPipeSink.Posix] with Linux values pins what the sink asks the kernel for and how it
 * maps the answers (Task 5 carries, review Important 2).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidPipeSinkTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** Kernel pipes as `Os` sees them: each pinned pipe gets the next of [sizes] and holds that many bytes, then EAGAIN. */
    private class FakePosix(vararg sizes: Int) : AndroidPipeSink.Posix {
        override val F_GETFL = 3
        override val F_SETFL = 4
        override val O_NONBLOCK = 0x800
        override val EAGAIN = 11
        val calls = mutableListOf<String>()
        var refusePin = false
        var readerGone = false
        private val sizes = ArrayDeque(sizes.toList())
        private val size = IdentityHashMap<FileDescriptor, Int>()
        private val held = IdentityHashMap<FileDescriptor, Int>()
        override fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int): Int {
            calls += "fcntl($cmd,$arg)"
            return when (cmd) {
                AndroidPipeSink.F_SETPIPE_SZ -> { if (refusePin) throw ErrnoException("fcntl", 1 /*EPERM*/); size[fd] = sizes.removeFirstOrNull() ?: arg; size[fd]!! }
                AndroidPipeSink.F_GETPIPE_SZ -> size[fd] ?: 16 * 4096
                F_GETFL -> 0x2 // O_RDWR-ish: kept
                F_SETFL -> 0
                else -> error("unexpected fcntl $cmd")
            }
        }
        override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
            if (readerGone) throw ErrnoException("write", 32 /*EPIPE*/)
            val h = held[fd] ?: 0
            if (h + count > (size[fd] ?: 65_536)) throw ErrnoException("write", EAGAIN)
            held[fd] = h + count
            return count
        }
    }

    private val created = mutableListOf<ParcelFileDescriptor>()
    private fun pipe(): Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe().also { created += it }
    private fun ParcelFileDescriptor.isClosed() = runCatching { fd }.isFailure

    @Test fun theWriteEndIsPinnedToTheSizeReadBackAndMadeNonBlocking() {
        val (read, write) = pipe()
        val posix = FakePosix(65_536)
        val sink = AndroidPipeSink(write, posix)
        assertEquals(listOf("fcntl(1031,65536)", "fcntl(1032,0)", "fcntl(3,0)", "fcntl(4,${0x2 or 0x800})"), posix.calls)
        assertEquals(65_536, sink.pipeSize)
        sink.close(); read.close()
    }

    @Test fun aRefusedPinFailsClosedAndNothingIsCached() {
        val posix = FakePosix().apply { refusePin = true }
        val (read, write) = pipe()
        assertFailsWith<IOException> { AndroidPipeSink(write, posix) }
        write.close(); read.close()
        val pipes = AndroidPipes(posix, ::pipe)
        assertFailsWith<IOException> { pipes.capacity() }
        assertFailsWith<IOException> { pipes.openFeed() }
        assertTrue(created.all { it.isClosed() }, "every fd closed on the refused paths")
        posix.refusePin = false
        assertEquals(64_000L, pipes.capacity(), "the refused run left no measurement behind")
    }

    @Test fun aFeedPipeBiggerThanTheMeasuredOneFailsClosed() {
        val pipes = AndroidPipes(FakePosix(65_536, 262_144), ::pipe)
        assertEquals(64_000L, pipes.capacity())
        assertFailsWith<IOException> { pipes.openFeed() }
        assertTrue(created.all { it.isClosed() })
    }

    @Test fun aFeedPipeLikeTheMeasuredOneCarriesTheMeasuredCapacity() {
        val pipes = AndroidPipes(FakePosix(65_536, 65_536, 32_768), ::pipe)
        val feed = pipes.openFeed()
        assertEquals(64_000L, feed.capacityBytes)
        feed.sink.close(); feed.read.close()
        val smaller = pipes.openFeed() // a smaller pipe can only make the guard and probe stricter
        assertEquals(64_000L, smaller.capacityBytes)
        smaller.sink.close(); smaller.read.close()
    }

    @Test fun eagainIsFullAndEpipeIsClosed() {
        val (read, write) = pipe()
        val posix = FakePosix(65_536)
        val sink = AndroidPipeSink(write, posix)
        assertEquals(64_000L, PipeCapacity.measure(sink), "fills to EAGAIN without blocking or throwing")
        assertEquals(PipeSink.FULL, sink.tryWrite(ByteArray(3_200)))
        posix.readerGone = true
        assertEquals(PipeSink.CLOSED, sink.tryWrite(ByteArray(3_200)))
        sink.close(); read.close()
    }

    /** Review minor 2: half a chunk in the pipe can't be re-sent whole (PCM16 would shift), so the feed ends. */
    @Test fun aShortWriteEndsTheFeed() {
        val (read, write) = pipe()
        val sink = AndroidPipeSink(write, object : AndroidPipeSink.Posix by FakePosix(65_536) {
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int) = count - 1
        })
        assertEquals(PipeSink.CLOSED, sink.tryWrite(ByteArray(3_200)))
        sink.close(); read.close()
    }

    /** Task 6 Step 1: LJSpeech LJ001-0013 ("than in the same operations with ugly ones.", public domain), 16 kHz mono PCM16 + silence. */
    @Test fun theProbeClipIsBundledVerbatim() {
        val bytes = ctx.assets.open(ExternalAudioProbe.ASSET).use { it.readBytes() }
        assertEquals(134_142, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("ceee25a814f40c58a3758eec605c7af951842802917f4a720346641bb8073c33", sha)
    }
}
