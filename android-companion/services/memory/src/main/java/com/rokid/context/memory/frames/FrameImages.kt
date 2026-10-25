package com.rokid.context.memory.frames

import android.content.Context
import com.rokid.context.core.log.PcLog
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.rokid.context.memory.db.PrefsModeStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Seals and opens one frame file's bytes (Gaps G1). Frames are small (360 px max, q62), so whole-buffer AEAD is enough. */
interface FrameCipher {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** Tink AES-256-GCM. In the app the keyset is wrapped by an Android Keystore key ([keystore]); tests use [inMemoryForTests]. */
class TinkFrameCipher(private val aead: Aead) : FrameCipher {
    override fun seal(plain: ByteArray): ByteArray = aead.encrypt(plain, AD)
    override fun open(sealed: ByteArray): ByteArray = aead.decrypt(sealed, AD)

    companion object {
        private val AD = "pca-frame-v1".toByteArray(Charsets.US_ASCII)
        /** In `memory_graph.xml` beside the DB mode flags: that file is excluded from backup (a keyset never restores without its Keystore key). */
        private const val PREFS = "memory_graph"
        private const val KEYSET = "frame_keyset"
        private const val MASTER_KEY_URI = "android-keystore://pca_frame_master_key"

        /** Opens the Keystore: never on Main. Refuses a keyset Tink could not wrap with the Keystore (it would sit in the clear). */
        fun keystore(context: Context): TinkFrameCipher {
            AeadConfig.register()
            val manager = AndroidKeysetManager.Builder()
                .withSharedPref(context.applicationContext, KEYSET, PREFS)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
            if (!manager.isUsingKeystore) {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEYSET).commit()
                throw IllegalStateException("Android Keystore unavailable")
            }
            return TinkFrameCipher(manager.keysetHandle.getPrimitive(Aead::class.java))
        }

        fun inMemoryForTests(): TinkFrameCipher {
            AeadConfig.register()
            return TinkFrameCipher(KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java))
        }
    }
}

/**
 * The one way to read or write a frame JPEG on the phone (Gaps G1, spec 6.1 "Frame JPEGs remain on disk (encrypted
 * when enabled)"). A sealed file is [MAGIC] + Tink ciphertext, under the same name, so frame rows, ids, the archive
 * move, retention, Delete all and the on-disk size accounting are unchanged. Readers ([open], [readBytes]) take plain
 * and sealed files alike. Failures keep the data and log a fixed [Code] only (never a path, a name or a message).
 */
object FrameImages {
    private const val TAG = "FrameImages"
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'C'.code.toByte(), 'A'.code.toByte(), 'F'.code.toByte(), 1)

    /** The in-place conversion's copy; not a frame extension, so ingest, retention and the id sweep never take it. */
    const val TEMP_SUFFIX = ".pcaswap"

    enum class Code { KEY, SEAL, OPEN, READ, WRITE, VERIFY, SWAP }
    enum class Convert { DONE, SKIPPED, FAILED }

    @Volatile private var wanted: () -> Boolean = { false }
    @Volatile private var cipherFactory: (() -> FrameCipher)? = null
    @Volatile private var cipher: FrameCipher? = null

    /** App start: frames follow "Encrypt memory" as asked (the pending target, else the applied mode). */
    fun install(context: Context) {
        val app = context.applicationContext
        val store = PrefsModeStore(app)
        wanted = { store.target ?: store.current ?: false }
        cipherFactory = { TinkFrameCipher.keystore(app) }
        cipher = null
    }

    fun configureForTests(testCipher: FrameCipher?, wantedNow: () -> Boolean) {
        cipher = testCipher; cipherFactory = testCipher?.let { c -> { c } }; wanted = wantedNow
    }

    /** Whether frames should be sealed now. */
    fun encryptionWanted(): Boolean = runCatching { wanted() }.getOrDefault(false)

    private fun cipher(): FrameCipher = cipher ?: synchronized(this) {
        cipher ?: (cipherFactory ?: throw IllegalStateException("no frame cipher")).invoke().also { cipher = it }
    }

    fun isSealed(bytes: ByteArray): Boolean = bytes.size > MAGIC.size && (MAGIC.indices).all { bytes[it] == MAGIC[it] }

    fun isSealed(file: File): Boolean = runCatching {
        file.inputStream().use { ins ->
            val head = ByteArray(MAGIC.size + 1)
            var n = 0
            while (n < head.size) { val r = ins.read(head, n, head.size - n); if (r < 0) break; n += r }
            n == head.size && isSealed(head)
        }
    }.getOrDefault(false)

    /**
     * The bytes to put on disk for a new frame: sealed while encryption is wanted, else [plain] as is. Throws
     * [IOException] when sealing fails: a new frame is never written in the clear while the user asked for encryption
     * (the glasses keep an unacked frame and resend it).
     */
    @Throws(IOException::class)
    fun forDisk(plain: ByteArray): ByteArray {
        if (!encryptionWanted()) return plain
        return try { seal(plain) } catch (e: Exception) { fail(Code.SEAL, e); throw IOException("frame not sealed") }
    }

    private fun seal(plain: ByteArray): ByteArray = MAGIC + cipher().seal(plain)
    private fun unseal(bytes: ByteArray): ByteArray = cipher().open(bytes.copyOfRange(MAGIC.size, bytes.size))

    /** The frame's plain bytes; null when missing, empty, unreadable or a sealed file the key can't open. Never throws. */
    fun readBytes(path: String?): ByteArray? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        val raw = try { file.readBytes() } catch (e: Exception) { fail(Code.READ, e); return null }
        if (!isSealed(raw)) return raw
        return try { unseal(raw) } catch (e: Exception) { fail(Code.OPEN, e); null }
    }

    /** [readBytes] as a stream (thumbnails, viewer, vision input). */
    fun open(path: String?): InputStream? = readBytes(path)?.let(::ByteArrayInputStream)

    /**
     * Converts one file in place to [toSealed]: the converted copy is written beside it ([TEMP_SUFFIX]), fsynced and
     * checked, then [commit] runs the swap (rename over the original, which keeps its name and time) only if the
     * original is still there unchanged. Any failure leaves the original as it was and drops the copy.
     * SKIPPED = already in that form, or gone/changed meanwhile. [beforeCommit] lets a test stop at the worst moment.
     */
    suspend fun convert(
        file: File,
        toSealed: Boolean,
        commit: suspend (swap: () -> Boolean) -> Boolean = { it() },
        beforeCommit: () -> Unit = {},
    ): Convert {
        if (!file.isFile) return Convert.SKIPPED
        val length = file.length(); val modified = file.lastModified()
        val raw = try { file.readBytes() } catch (e: Exception) { fail(Code.READ, e); return Convert.FAILED }
        if (raw.isEmpty() || isSealed(raw) == toSealed) return Convert.SKIPPED
        val out = try { if (toSealed) seal(raw) else unseal(raw) } catch (e: Exception) { fail(if (toSealed) Code.SEAL else Code.OPEN, e); return Convert.FAILED }
        val verified = try { if (toSealed) unseal(out).contentEquals(raw) else true } catch (e: Exception) { false }
        if (!verified) { fail(Code.VERIFY, null); return Convert.FAILED }
        val dir = file.parentFile ?: return Convert.FAILED
        val temp = File(dir, file.name + TEMP_SUFFIX)
        try {
            FileOutputStream(temp).use { o -> o.write(out); o.fd.sync() }
            temp.setLastModified(modified)
        } catch (e: Exception) {
            temp.delete(); fail(Code.WRITE, e); return Convert.FAILED
        }
        beforeCommit()
        val swapped = try {
            commit { file.isFile && file.length() == length && file.lastModified() == modified && temp.renameTo(file) }
        } catch (e: Exception) {
            temp.delete(); fail(Code.SWAP, e); return Convert.FAILED
        }
        if (!swapped) { temp.delete(); return Convert.SKIPPED }
        fsyncDir(dir)
        return Convert.DONE
    }

    /** Deletes conversion copies a process death left behind (the originals are whole). Returns how many went. */
    fun sweepTemps(dir: File): Int = dir.listFiles().orEmpty().count { it.isFile && it.name.endsWith(TEMP_SUFFIX) && it.delete() }

    /** A fixed code and the exception class only: a message may carry a path (frame ids are in file names). */
    fun fail(code: Code, e: Throwable?) {
        if (e == null) PcLog.w(TAG, "frame crypto failed code=$code") else PcLog.w(TAG, "frame crypto failed code=$code (${e.javaClass.simpleName})")
    }

    private fun fsyncDir(dir: File) {
        runCatching { FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
