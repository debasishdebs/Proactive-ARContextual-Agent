package com.rokid.context.data.frames

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Makes the renames and deletes in one directory durable. Injectable so tests can fail one (spec §P2 receipt durability). */
fun interface DirSync {
    /** True once [dir]'s entries reached the disk; false when it cannot be opened or synced. */
    fun sync(dir: File): Boolean

    companion object {
        /** Tests only (Robolectric has no real `Os.open` on a directory): replaces [Default]'s implementation while set. */
        @Volatile var testOverride: DirSync? = null

        private val os = DirSync { dir ->
            try {
                val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd); true } finally { try { Os.close(fd) } catch (_: ErrnoException) { } }
            } catch (e: ErrnoException) {
                false
            }
        }

        /** `Os.open(dir, O_RDONLY)` + `Os.fsync`. */
        val Default = DirSync { dir -> (testOverride ?: os).sync(dir) }
    }
}
