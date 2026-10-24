package com.rokid.context.canvas.outbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The production [PhoneFilePort]: deletes a file only when its canonical path lies strictly inside one of the app's own [roots] (a `..` segment, a symlink, a sibling directory that
 * shares a name prefix or a relative path is refused), never a directory, and answers DONE only when the file is confirmed gone (already missing is done).
 */
class PhoneFileCleanup(roots: List<File>) : PhoneFilePort {
    private val roots = roots.map { it.canonicalFile }

    override suspend fun deleteConfirmed(path: String): CleanupResult = withContext(Dispatchers.IO) {
        try {
            val f = File(path)
            if (path.isBlank() || !f.isAbsolute) return@withContext CleanupResult.FAILED
            val c = f.canonicalFile
            if (roots.none { c.path.startsWith(it.path + File.separator) }) return@withContext CleanupResult.FAILED
            when {
                !c.exists() -> CleanupResult.DONE
                c.isDirectory -> CleanupResult.FAILED
                else -> { c.delete(); if (c.exists()) CleanupResult.FAILED else CleanupResult.DONE }
            }
        } catch (e: java.io.IOException) { CleanupResult.FAILED } catch (e: SecurityException) { CleanupResult.FAILED }
    }
}
