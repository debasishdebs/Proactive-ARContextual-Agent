package com.rokid.context.voice.android

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.voice.ExternalAudioProbe
import com.rokid.context.voice.PipeCapacity
import com.rokid.context.voice.PipeSink
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.FileDescriptor
import java.security.MessageDigest

/**
 * Robolectric stubs `android.system.Os` (writes return 0), so the real pipe is PENDING-DEVICE; these pin what the sink
 * asks the kernel for and how it maps the answers (Task 5 carries).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidPipeSinkTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** A kernel pipe as `Os` sees it: [capacity] bytes, then EAGAIN; EPIPE once [readerGone]. */
    private class FakePosix(var capacity: Int = 64_000, private val refusePin: Boolean = false) : AndroidPipeSink.Posix {
        val calls = mutableListOf<String>()
        var flags = 0x2 // O_RDWR-ish: other flags are kept
        var held = 0
        var readerGone = false
        override fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int): Int {
            calls += "fcntl($cmd,$arg)"
            if (cmd == AndroidPipeSink.F_SETPIPE_SZ) { if (refusePin) throw ErrnoException("fcntl", OsConstants.EPERM); return arg }
            return flags // F_GETFL (F_SETFL's return is ignored); Robolectric gives both the value 0
        }
        override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int {
            if (readerGone) throw ErrnoException("write", OsConstants.EPIPE)
            if (held + count > capacity) throw ErrnoException("write", OsConstants.EAGAIN)
            held += count
            return count
        }
    }

    private fun pipe(): Pair<ParcelFileDescriptor, ParcelFileDescriptor> = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }

    @Test fun theWriteEndIsPinnedTo64KiBThenMadeNonBlocking() {
        val (read, write) = pipe()
        val posix = FakePosix()
        AndroidPipeSink(write, posix).close(); read.close()
        // F_SETPIPE_SZ(65536) first, so the measured pipe is the pinned one; then O_NONBLOCK is added to the other flags.
        // (Robolectric's OsConstants give F_GETFL = F_SETFL = O_NONBLOCK = 0: the real flag change is PENDING-DEVICE.)
        assertEquals(listOf("fcntl(1031,65536)", "fcntl(${OsConstants.F_GETFL},0)", "fcntl(${OsConstants.F_SETFL},${0x2 or OsConstants.O_NONBLOCK})"), posix.calls)
    }

    @Test fun aRefusedPinStillGivesANonBlockingSink() {
        val (read, write) = pipe()
        val posix = FakePosix(refusePin = true)
        val sink = AndroidPipeSink(write, posix)
        assertEquals(3, posix.calls.size, "the flags are still set after the pin is refused")
        assertEquals(PipeSink.WROTE, sink.tryWrite(ByteArray(3_200)))
        sink.close(); read.close()
    }

    @Test fun eagainIsFullAndEpipeIsClosed() {
        val (read, write) = pipe()
        val posix = FakePosix(capacity = 64_000)
        val sink = AndroidPipeSink(write, posix)
        assertEquals(64_000L, PipeCapacity.measure(sink), "fills to EAGAIN without blocking or throwing")
        assertEquals(PipeSink.FULL, sink.tryWrite(ByteArray(3_200)))
        posix.readerGone = true
        assertEquals(PipeSink.CLOSED, sink.tryWrite(ByteArray(3_200)))
        sink.close(); read.close()
    }

    @Test fun aShortWriteIsNeverCountedAsWritten() {
        val (read, write) = pipe()
        val sink = AndroidPipeSink(write, object : AndroidPipeSink.Posix {
            override fun fcntlInt(fd: FileDescriptor, cmd: Int, arg: Int) = 0
            override fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int) = count - 1
        })
        assertEquals(PipeSink.FULL, sink.tryWrite(ByteArray(3_200)))
        sink.close(); read.close()
    }

    /** Task 6 Step 1: the phase 0 clip ("what do I need from the store", macOS `say`, 16 kHz mono PCM16 + 3 s of silence). */
    @Test fun theProbeClipIsBundledVerbatim() {
        val bytes = ctx.assets.open(ExternalAudioProbe.ASSET).use { it.readBytes() }
        assertEquals(134_142, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("09dbcaebf6fc6521eb95d678a08ce0a9d3940a22c7b55493f2db0bbb8e42f34a", sha)
    }
}
