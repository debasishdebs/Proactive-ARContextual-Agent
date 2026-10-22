package com.rokid.context.canvas.trace

import androidx.room.withTransaction
import com.rokid.context.memory.db.ContextDatabase
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Filesystem publication under the deletion fence (S57, S58). A trace, an import or a rewrite is written to `files/canvas_tmp`, and only
 * [publish] moves it to its final place under `files/canvas_traces` or `files/canvas_import`, and only if [publish]'s fence still holds INSIDE the
 * database transaction (so a Forget or Delete all that committed its epoch or tombstone first wins). A refused or failed publish deletes the tmp file.
 * [sweepTmp] removes every orphan tmp file at start-up (a crash between write and publish). All three folders are deleted by Delete all and
 * excluded from backup and device transfer.
 */
class FencedFiles(private val db: ContextDatabase, private val filesDir: File) {
    val tmpDir: File get() = File(filesDir, TMP_DIR)

    /** A fresh tmp file path (the folder is created); the caller writes it, then calls [publish]. */
    fun newTmp(prefix: String): File {
        require(prefix.isNotEmpty() && prefix.all { it.isLetterOrDigit() || it == '-' || it == '_' }) { "bad tmp prefix" }
        tmpDir.mkdirs()
        return File.createTempFile("$prefix-", ".tmp", tmpDir)
    }

    /**
     * Moves [tmp] (which must be under [TMP_DIR]) to [final] (which must be under [TRACES_DIR] or [IMPORT_DIR]) atomically, but only if [fence] returns true
     * inside the transaction. Returns whether it was published; on false the tmp file is gone and nothing is left at [final] (a failed move or commit rolls the
     * transaction back, including any row [fence] wrote). [fence] runs on the transaction, so it may query the DB.
     */
    suspend fun publish(tmp: File, final: File, fence: suspend () -> Boolean): Boolean {
        require(isUnder(tmp, TMP_DIR)) { "tmp must be under $TMP_DIR" }
        require(isUnder(final, TRACES_DIR) || isUnder(final, IMPORT_DIR)) { "final must be under $TRACES_DIR or $IMPORT_DIR" }
        var moved = false
        return try {
            db.withTransaction {
                if (!fence()) { tmp.deleteRecursively(); return@withTransaction false }
                final.parentFile?.mkdirs()
                // A failed move THROWS so the transaction rolls back: a row the fence lambda wrote (the way to make the row and the file atomic) must not commit without its file.
                Files.move(tmp.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
                moved = true
                true
            }
        } catch (e: Exception) {
            // The move failed, or the commit failed AFTER the move (the row is rolled back): neither a tmp file nor an orphan final file may stay behind.
            discard(tmp, final, moved)
            if (e is CancellationException) throw e
            false
        }
    }

    /** After a failed move or commit: neither a tmp path nor a moved final path stays. Both may be NON-EMPTY directories (an import), so `delete()` would leave their chunks behind. */
    internal fun discard(tmp: File, final: File, moved: Boolean) {
        tmp.deleteRecursively()
        if (moved) final.deleteRecursively()
    }

    /** Start-up: deletes everything under `files/canvas_tmp` (nothing there is complete). Returns the files removed. Never throws. */
    fun sweepTmp(): Int = sweepTmp(filesDir)

    private fun isUnder(f: File, dir: String): Boolean {
        val root = File(filesDir, dir).canonicalFile
        val c = f.canonicalFile
        return c != root && c.path.startsWith(root.path + File.separator)
    }

    companion object {
        const val TRACES_DIR = "canvas_traces"
        const val IMPORT_DIR = "canvas_import"
        const val TMP_DIR = "canvas_tmp"

        /** The folders Delete all removes (all three), as names under `filesDir`. */
        val DELETE_ALL_DIRS = listOf(TRACES_DIR, IMPORT_DIR, TMP_DIR)

        fun sweepTmp(filesDir: File): Int = try {
            val dir = File(filesDir, TMP_DIR)
            dir.walkBottomUp().filter { it != dir }.count { it.delete() }
        } catch (e: Exception) { 0 }
    }
}
