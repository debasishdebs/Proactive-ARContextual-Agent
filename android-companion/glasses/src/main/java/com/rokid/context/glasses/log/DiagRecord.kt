package com.rokid.context.glasses.log

import java.io.File
import java.io.FileOutputStream

/**
 * The glasses' diagnostic-recording state as reported to the phone (LGC6-min puts [wire] into `Hello.diagFlagState`; LGC4P re-sends the control while it reads
 * `armed_no_epoch`). Three-state bootstrap of the plan (section 3.4) plus one extra reading:
 *  - [UNPROVISIONED]: NOT requested AND no durable epoch. The expected default of a glasses that never met a phone (not a fault), but it is also what a PROVISIONED glasses reads
 *    while a deletion fence is in progress or after a failed epoch write (the old epoch is cleared first); it does not prove "never contacted".
 *  - [ARMED_NO_EPOCH]: a phone asked for diagnostics (`HudSettings.diagRecord`, persisted) but no deletion epoch is durably saved yet. Records NOTHING.
 *  - [ENABLED]: asked AND the epoch is durable: the trace gate is open.
 *  - [DISABLED]: an epoch is saved but diagnostics are not requested (Developer mode off, or an old phone).
 */
enum class DiagFlagState(val wire: String) {
    UNPROVISIONED("unprovisioned"), ARMED_NO_EPOCH("armed_no_epoch"), ENABLED("enabled"), DISABLED("disabled"),
}

/**
 * `files/diag_state`: ONE tiny text file, `1` (diagnostics requested) or `0`; a missing, unreadable or unrecognised file means NOT requested (the safe default).
 * Chosen over a SharedPreferences boolean because it needs no `Context` (the path comes from `dataDir`, so nothing at startup touches the main thread), its commit
 * is explicit and checked (write a temp file, fsync, rename; the result is returned), a test can fail it, and the epoch it must stay coherent with is a plain
 * file in the same directory family. [write] and [read] are seams (the production ones are real file I/O, never called on the main thread: [DiagRecord] runs
 * them on its writer).
 */
class DiagStateFile(
    val file: File,
    private val write: (File, String) -> Boolean = ::atomicWrite,
    private val read: (File) -> String? = { f -> try { f.takeIf { it.isFile }?.readText(Charsets.UTF_8) } catch (e: Exception) { null } },
) {
    /** True only for a file that says `1`. */
    fun load(): Boolean = read(file)?.trim() == "1"

    /**
     * Saves [requested]; true when the value is durably what was asked. A failed `0` write falls back to deleting the file (absent = off), so a failed "off" can
     * never leave a `1` that re-enables recording after a restart.
     */
    fun store(requested: Boolean): Boolean {
        val ok = try { write(file, if (requested) "1\n" else "0\n") } catch (e: Exception) { false }
        if (ok) return true
        if (requested) return false
        return try { file.delete() || !file.exists() } catch (e: Exception) { false }
    }

    companion object {
        /** `dataDir/files/diag_state`, a plain string path (no `filesDir` getter: nothing touches the filesystem on the calling thread). */
        fun forApp(context: android.content.Context): DiagStateFile = DiagStateFile(File(GlassesLogging.logDir(context), "diag_state"))

        /** Temp file, fsync, rename (the same-directory rename is atomic): a torn write never replaces the old value. */
        fun atomicWrite(f: File, text: String): Boolean {
            val dir = f.parentFile ?: return false
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            val part = File(dir, f.name + ".part")
            return try {
                FileOutputStream(part).use { it.write(text.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                part.renameTo(f) || run { f.delete(); part.renameTo(f) }
            } catch (e: Exception) { false }
        }
    }
}

/**
 * LGC4: the persisted `diag_record` request and THE single trace-gate predicate [diagEnabled].
 *
 * `HudSettings.diagRecord` (phone Developer mode) arrives on the settings path and is only a REQUEST ([requested]); it becomes effective ([diagEnabled]) only when
 * [epochSaved] says the deletion epoch of the arriving `TraceControl` is durable. [epochSaved] is the recorder's own durable-epoch fact, set after its ordered
 * wipe (the deletion fence) has run and the epoch file was written, so the order on the glasses is always: (1) fence/wipe, (2) epoch saved, (3) the request
 * takes effect. There is no second copy of the epoch here, so the two can never disagree. Settings first, control first, or a restart between them all converge.
 *
 * Threading: [diagEnabled] / [state] are lock-free volatile reads for the sensor/session paths. The load and every save run on [io] (the single storage writer,
 * in order), never on the calling thread: construction and [onSettings] do no filesystem work. Until the queued load has run, [requested] is false (nothing records).
 *
 * The glasses LOG policy (`capture_diag.log`) does NOT follow this flag in LG-MIN: see [GlassesLogging.DIAGNOSTIC_RECORD].
 */
class DiagRecord(
    private val store: DiagStateFile,
    private val epochSaved: () -> Boolean,
    private val io: (Runnable) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var requestedNow = false
    /** Runs on the writer after the REQUEST changed (the recorder closes a running diagnostic trace when it went off). */
    @Volatile var onChanged: () -> Unit = {}
    /** The last save did not reach the disk: a restart would forget (or, for an "off", keep) the old value. Retried at the next [onSettings]. */
    @Volatile var persistFailed = false
        private set

    init {
        io {
            try { requestedNow = store.load() } catch (e: Exception) { log("diag state not read: ${e.javaClass.simpleName}") }
        }
    }

    /** Retries a failed save without waiting for the next settings push (the runtime calls it at every `TraceControl`, which the phone also re-sends while `armed_no_epoch`). */
    fun retryPersist() {
        io { if (persistFailed) { val ok = try { store.store(requestedNow) } catch (e: Exception) { false }; persistFailed = !ok } }
    }

    fun requested(): Boolean = requestedNow

    /** THE predicate of the trace gate: requested AND the epoch is durable. */
    fun diagEnabled(): Boolean = requestedNow && epochSaved()

    fun state(): DiagFlagState = stateFor(epochSaved())

    /** [state] for an epoch fact the caller already has (the boot line reads it from the recovery, without touching the recorder). */
    fun stateFor(saved: Boolean): DiagFlagState {
        return when {
            requestedNow && saved -> DiagFlagState.ENABLED
            requestedNow -> DiagFlagState.ARMED_NO_EPOCH
            saved -> DiagFlagState.DISABLED
            else -> DiagFlagState.UNPROVISIONED
        }
    }

    /** `HudSettings.diagRecord` from the phone (link thread). Applied and saved on the writer, after the queued load. A repeat of the same value saves only to retry a failed save. */
    fun onSettings(diagRecord: Boolean) {
        io {
            val changed = requestedNow != diagRecord
            requestedNow = diagRecord
            if (changed || persistFailed) {
                val ok = try { store.store(diagRecord) } catch (e: Exception) { false }
                persistFailed = !ok
                if (!ok) log("diag state not saved")
            }
            if (changed) {
                log("diag record requested=$diagRecord state=${state().wire}")
                try { onChanged() } catch (e: Exception) { log("diag change hook failed: ${e.javaClass.simpleName}") }
            }
        }
    }
}
