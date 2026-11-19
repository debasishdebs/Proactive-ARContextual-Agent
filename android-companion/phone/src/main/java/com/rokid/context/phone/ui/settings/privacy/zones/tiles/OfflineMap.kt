package com.rokid.context.phone.ui.settings.privacy.zones.tiles

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.log.PcLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Where the selected map file lives: a file the user keeps elsewhere and the app only reads ([LINKED]), or a private copy inside the app ([IMPORTED]). */
enum class MapMode { LINKED, IMPORTED }

/** What is remembered about the selection: a content URI (or, for an import, nothing but the mode), and the display name. Never logged. */
data class MapSelection(val mode: MapMode, val uri: String?, val name: String)

/**
 * The selection, in the phone prefs file [PREFS] (device state: excluded from backup and transfer, because a read grant does not survive a restore).
 * Held in memory only when [prefs] is null (tests).
 */
class OfflineMapStore(private val prefs: SharedPreferences? = null) {
    private var mem: MapSelection? = null

    fun get(): MapSelection? {
        prefs ?: return mem
        val mode = prefs.getString(K_MODE, null)?.let { m -> MapMode.entries.firstOrNull { it.name == m } } ?: return null
        val uri = prefs.getString(K_URI, null)
        if (mode == MapMode.LINKED && uri.isNullOrBlank()) return null
        return MapSelection(mode, uri, prefs.getString(K_NAME, "") ?: "")
    }

    fun set(s: MapSelection?) {
        mem = s
        val e = prefs?.edit() ?: return
        if (s == null) e.clear() else e.putString(K_MODE, s.mode.name).putString(K_URI, s.uri).putString(K_NAME, s.name)
        e.apply()
    }

    companion object {
        const val PREFS = "offline_map"
        private const val K_MODE = "mode"
        private const val K_URI = "uri"
        private const val K_NAME = "name"

        @Volatile private var instance: OfflineMapStore? = null
        fun get(context: Context): OfflineMapStore = instance ?: synchronized(this) {
            instance ?: OfflineMapStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}

/** What the zones screen needs of the platform for map files. The Android version uses the Storage Access Framework; tests use a fake. No network is reachable through it. */
interface MapFiles {
    /** The app-private folder that holds an imported copy (under files/, excluded from backup and transfer). */
    val importDir: File
    /** Keeps a read-only grant on a picked document across restarts. Throws when the URI is not a content URI or the grant is refused. */
    fun grant(uri: String)
    fun release(uri: String)
    fun open(uri: String): ByteSource
    /** The document's display name and size in bytes; null when it cannot be asked. */
    fun describe(uri: String): Pair<String, Long>?
    /** Streams the document into [dest] and returns the bytes written. */
    fun copy(uri: String, dest: File): Long
}

/** The two things the zones screen is built with to offer an offline map (null in builds and tests with no map support). */
class OfflineMapDeps(val files: MapFiles, val store: OfflineMapStore)

/** Only a content URI from the document picker is accepted: never http(s), never a bare path. */
object MapUri {
    fun accepted(uri: String): Boolean = uri.substringBefore(':', "") == "content"
}

data class OfflineMapUi(
    val selection: MapSelection? = null,
    /** Size of the file in words, when known (the imported copy's size, or the linked file's). */
    val sizeBytes: Long? = null,
    val minZoom: Int = 0,
    val maxZoom: Int = 0,
    val attribution: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** The map file is open and tiles are being drawn. */
    val ready: Boolean = false,
)

object OfflineMapWords {
    const val NONE = "No offline map is selected: the plain grid is used. Pick a PMTiles file you built on your computer. Online street tiles are used only if you turn on the street map switch."
    const val NOT_URI = "That is not a file from the document picker. Nothing was changed."
    const val NO_ROOM = "There is not enough free space to copy that file into the app. Nothing was changed."
    const val CANT_OPEN = "The offline map file could not be opened (it may have been moved, or access was withdrawn). The plain grid is used. Remove it or pick the file again."

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * Owns the offline map selection and the open archive. A picked file is only ever read through the platform's read-only grant; [importCopy] is the one path that copies it,
 * into the app-private [MapFiles.importDir]. Anything unreadable leaves the plain canvas in use ("fails closed") and says why. Logs are literal.
 */
class OfflineMapViewModel(
    private val files: MapFiles,
    private val store: OfflineMapStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(OfflineMapUi(selection = store.get(), busy = store.get() != null))
    val ui: StateFlow<OfflineMapUi> = _ui.asStateFlow()

    private val _reader = MutableStateFlow<PmTilesReader?>(null)
    /** The open archive the map draws from; null means the plain canvas. */
    val reader: StateFlow<PmTilesReader?> = _reader.asStateFlow()

    private val importedFile: File get() = File(files.importDir, IMPORT_NAME)

    init { restore() }

    private fun restore() {
        val sel = store.get() ?: run { _ui.update { it.copy(busy = false) }; return }
        viewModelScope.launch(io) {
            val r = try { openSelection(sel) } catch (e: PmTilesException) { fail(sel, e.reason.words); null } catch (e: Exception) { fail(sel, OfflineMapWords.CANT_OPEN); null }
            if (r != null) publish(sel, r, "Offline map ready.")
        }
    }

    /** The user picked a file to read in place. */
    fun link(uri: String) {
        if (!MapUri.accepted(uri)) { _ui.update { it.copy(message = OfflineMapWords.NOT_URI, messageIsError = true) }; return }
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(io) {
            val old = store.get()
            try { files.grant(uri) } catch (e: Exception) { PcLog.i(TAG, "offline map grant refused"); _ui.update { it.copy(busy = false, message = OfflineMapWords.CANT_OPEN, messageIsError = true) }; return@launch }
            val sel = MapSelection(MapMode.LINKED, uri, files.describe(uri)?.first ?: "Map file")
            val r = try { openSelection(sel) } catch (e: Exception) {
                if (uri != old?.uri) runCatching { files.release(uri) }
                rejected(if (e is PmTilesException) e.reason.words else OfflineMapWords.CANT_OPEN)
                return@launch
            }
            discard(old, keepUri = uri)
            store.set(sel)
            publish(sel, r, "Offline map linked. The file stays where it is; the app only reads it.")
            PcLog.i(TAG, "offline map linked")
        }
    }

    /** The user chose "Import (copy) into the app": the one case where the file is copied. */
    fun importCopy(uri: String) {
        if (!MapUri.accepted(uri)) { _ui.update { it.copy(message = OfflineMapWords.NOT_URI, messageIsError = true) }; return }
        _ui.update { it.copy(busy = true, message = null) }
        viewModelScope.launch(io) {
            val dir = files.importDir
            dir.mkdirs()
            val tmp = File(dir, "$IMPORT_NAME.tmp")
            val size = files.describe(uri)?.second
            if (size != null && size > dir.usableSpace - SPACE_MARGIN) { rejected(OfflineMapWords.NO_ROOM); return@launch }
            try {
                files.copy(uri, tmp)
                // validate the copy before it replaces anything
                PmTilesReader.open(FileByteSource(tmp)).close()
            } catch (e: PmTilesException) { tmp.delete(); rejected(e.reason.words); return@launch } catch (e: Exception) { tmp.delete(); rejected(OfflineMapWords.CANT_OPEN); return@launch }
            val old = store.get()
            closeReader()
            discard(old, keepUri = null)
            if (!tmp.renameTo(importedFile)) { tmp.delete(); rejected(OfflineMapWords.CANT_OPEN); store.set(null); publishNone(); return@launch }
            val sel = MapSelection(MapMode.IMPORTED, null, files.describe(uri)?.first ?: "Imported map")
            val r = try { openSelection(sel) } catch (e: Exception) { importedFile.delete(); store.set(null); publishNone(); rejected(OfflineMapWords.CANT_OPEN); return@launch }
            store.set(sel)
            publish(sel, r, "Offline map copied into the app.")
            PcLog.i(TAG, "offline map imported")
        }
    }

    /** Forgets the selection: drops the read grant (a linked file stays where it is) or deletes the private copy. */
    fun remove() {
        viewModelScope.launch(io) {
            val old = store.get()
            closeReader()
            discard(old, keepUri = null)
            store.set(null)
            publishNone("Offline map removed. The plain grid is used.")
            PcLog.i(TAG, "offline map removed")
        }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() { _reader.value?.let { runCatching { it.close() } }; _reader.value = null }

    private fun openSelection(sel: MapSelection): PmTilesReader {
        val src = when (sel.mode) {
            MapMode.LINKED -> files.open(sel.uri ?: throw PmTilesException(PmTilesException.Reason.UNREADABLE))
            MapMode.IMPORTED -> FileByteSource(importedFile)
        }
        return PmTilesReader.open(src)
    }

    private fun discard(old: MapSelection?, keepUri: String?) {
        if (old == null) return
        when (old.mode) {
            MapMode.LINKED -> old.uri?.takeIf { it != keepUri }?.let { runCatching { files.release(it) } }
            MapMode.IMPORTED -> importedFile.delete()
        }
    }

    private fun closeReader() { _reader.value?.let { runCatching { it.close() } }; _reader.value = null }

    private fun publish(sel: MapSelection, r: PmTilesReader, msg: String) {
        _reader.value?.takeIf { it !== r }?.let { runCatching { it.close() } }
        _reader.value = r
        val size = when (sel.mode) { MapMode.IMPORTED -> importedFile.length(); MapMode.LINKED -> sel.uri?.let { files.describe(it)?.second } }
        _ui.value = OfflineMapUi(sel, size, r.minZoom, r.maxZoom, r.attribution(), false, msg, false, ready = true)
    }

    private fun publishNone(msg: String? = null) { _ui.value = OfflineMapUi(message = msg) }

    private fun rejected(text: String) { _ui.update { it.copy(busy = false, message = text, messageIsError = true) } }

    /** A saved selection that no longer opens: the canvas is used, the selection stays so the user can see and remove it. */
    private fun fail(sel: MapSelection, text: String) {
        PcLog.i(TAG, "offline map unreadable")
        _reader.value = null
        _ui.value = OfflineMapUi(selection = sel, message = text, messageIsError = true)
    }

    companion object {
        const val IMPORT_NAME = "map.pmtiles"
        /** Folder name under files/ for an imported copy. */
        const val IMPORT_DIR = "offline_maps"
        private const val SPACE_MARGIN = 64L shl 20
        private const val TAG = "OfflineMap"
    }
}

/** A plain file read by position. */
class FileByteSource(private val file: File) : ByteSource {
    private val raf = try { java.io.RandomAccessFile(file, "r") } catch (e: java.io.IOException) { throw PmTilesException(PmTilesException.Reason.UNREADABLE) }
    override val size: Long get() = raf.length()
    override fun read(offset: Long, length: Int): ByteArray = synchronized(raf) {
        try {
            raf.seek(offset)
            ByteArray(length).also { raf.readFully(it) }
        } catch (e: java.io.EOFException) { throw PmTilesException(PmTilesException.Reason.CORRUPT) } catch (e: java.io.IOException) { throw PmTilesException(PmTilesException.Reason.UNREADABLE) }
    }
    override fun close() { runCatching { raf.close() } }
}
