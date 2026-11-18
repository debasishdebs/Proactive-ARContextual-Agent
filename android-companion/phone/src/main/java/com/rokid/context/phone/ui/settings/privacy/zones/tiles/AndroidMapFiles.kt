package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [MapFiles] over the Storage Access Framework. A document the user picked is read through a persisted READ-ONLY grant: no storage permission, no copy.
 * It reaches nothing but the content resolver and the app's own files directory (no network classes appear in this package; see MapNoNetworkTest).
 */
class AndroidMapFiles(context: Context) : MapFiles {
    private val app = context.applicationContext
    override val importDir: File get() = File(app.filesDir, OfflineMapViewModel.IMPORT_DIR)

    override fun grant(uri: String) {
        require(MapUri.accepted(uri))
        app.contentResolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun release(uri: String) {
        runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun open(uri: String): ByteSource {
        if (!MapUri.accepted(uri)) throw PmTilesException(PmTilesException.Reason.UNREADABLE)
        val pfd = try { app.contentResolver.openFileDescriptor(Uri.parse(uri), "r") } catch (e: Exception) { null } ?: throw PmTilesException(PmTilesException.Reason.UNREADABLE)
        return ChannelByteSource(java.io.FileInputStream(pfd.fileDescriptor).channel, pfd)
    }

    override fun describe(uri: String): Pair<String, Long>? = try {
        app.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else (c.getString(0) ?: "Map file") to (if (c.isNull(1)) 0L else c.getLong(1))
        }
    } catch (e: Exception) { null }

    override fun copy(uri: String, dest: File): Long {
        val input = app.contentResolver.openInputStream(Uri.parse(uri)) ?: throw IOException("unreadable")
        return input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }
}

/** Positional reads on an open file descriptor (a picked document is a seekable file for local storage). */
class ChannelByteSource(private val ch: FileChannel, private val owner: AutoCloseable) : ByteSource {
    override val size: Long get() = try { ch.size() } catch (e: IOException) { throw PmTilesException(PmTilesException.Reason.UNREADABLE) }
    override fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        try {
            var pos = offset
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n < 0) throw PmTilesException(PmTilesException.Reason.CORRUPT)
                pos += n
            }
        } catch (e: IOException) { throw PmTilesException(PmTilesException.Reason.UNREADABLE) }
        return buf.array()
    }
    override fun close() { runCatching { ch.close() }; runCatching { owner.close() } }
}
