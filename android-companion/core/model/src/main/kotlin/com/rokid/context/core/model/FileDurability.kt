package com.rokid.context.core.model

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Makes a rename durable: after `rename`, the directory entry itself must reach the disk too. */
object FileDurability {
    /** Fsyncs [dir]; false when it cannot be opened or synced (missing, or a platform that refuses it). */
    fun fsyncDir(dir: File): Boolean = try {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        true
    } catch (e: IOException) {
        false
    }
}
