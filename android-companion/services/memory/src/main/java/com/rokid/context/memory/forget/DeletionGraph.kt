package com.rokid.context.memory.forget

/**
 * The deletion inventory (slice 3a, S38, S58): EVERY `canvas_*` table, and every canvas/mark preference file and folder, with what Delete all does to it.
 * A new table, prefs file or folder that is not listed here fails `DeletionGraphInventoryTest` (tables, via Room's entity list) and `DeletionInventoryFilesTest`
 * (prefs and folders, via a source and backup-rules scan), so a later task cannot add an artefact and forget to delete it. Names only: this holds no data.
 */
object DeletionGraph {
    enum class Fate {
        /** Removed by Delete all (rows or files), and by the Forget cascades where the data is tied to a photo or a time range. */
        WIPED,
        /** Survives Delete all on purpose: it holds no user data (hashes, states, prompts, settings, the fence itself). The reason is the contract. */
        KEPT,
    }

    data class Entry(val name: String, val fate: Fate, val why: String)

    private fun w(n: String, why: String) = Entry(n, Fate.WIPED, why)
    private fun k(n: String, why: String) = Entry(n, Fate.KEPT, why)

    val TABLES: List<Entry> = listOf(
        w("canvas_trace", "IMU and hub traces (motion history); a Forget range tombstones them"),
        w("canvas_trace_event", "hub trace events"),
        w("canvas_replay", "replay results derived from traces and labels"),
        w("canvas_sweep", "sweep results derived from traces and labels"),
        w("canvas_label", "marks and intent labels (times, sources and enum names; no text)"),
        w("canvas_calibration", "measured-run calibration artifacts"),
        w("canvas_helper_job", "laptop-helper jobs; they carry photo ids"),
        k("canvas_helper_cleanup", "opaque job ids and reason codes only, no user data; must survive Delete all so the laptop copies are still deleted"),
        w("canvas_run", "photo experiment runs"), w("canvas_run_item", "per-photo run outputs"), w("canvas_score", "per-photo scores"),
        w("canvas_live_trace", "per-photo live outputs"),
        k("canvas_meta", "the monotonic labelRev counter (bumped by the wipe so no earlier result reads as current); the forgetRanges fence value is emptied by the wipe; otherwise no user data"),
        k("canvas_group_state", "publish-group recovery record: gen, hashes, mode, install id and state; needed to recover a deployed plan; no user data"),
        w("moment", "stored Moment projection (a rebuildable read model over frames, memories and marks)"),
        w("moment_part", "Moment membership edges (ids and revisions only)"),
        w("moment_tombstone", "forgotten seconds; the deletion epoch is the fence after Delete all, as with the frame tombstones"),
        w("moment_trace_dep", "reserved trace dependencies of a Moment; empty until a trace writer exists"),
        w("object_audit", "append-only audit of Moment-family objects: ids, op names, times and step names only, no content; deleted with its object and by Delete all"),
        w("object_derived", "derived SUMMARY, CAPTION and EMBEDDING fields of Moment-family objects; deleted with the object and by Delete all"),
        k("cleanup_outbox", "ids, paths and ranges only, no user content; unacknowledged rows survive Delete all so glasses, laptop and phone-file cleanup still happens; only acknowledged rows are removed"),
        k("canvas_pipeline", "pipelines are user-authored prompts and settings"), k("canvas_pipeline_version", "frozen pipeline versions: prompts"),
        k("canvas_publish", "publish records: hashes and snapshots of prompts, no photo data"),
    )

    /** Phone SharedPreferences names (without `.xml`). */
    val PREFS: List<Entry> = listOf(
        w("canvas_plan_state", "the publish-group and plan state sent to the glasses; cleared so a stale plan is never re-sent (only the generation counter stays: not personal data, and the glasses keep theirs)"),
        w("canvas_collect", "the Collect-for-tuning session window"),
        w("canvas_helper_grants", "the laptop helper's capability grants (py_exec, default off): a wipe turns every grant off, so Delete all never leaves the helper allowed"),
        k("deletion_epoch", "the persisted deletion epoch (install id, counter, wipedAtMs): it IS the fence and holds no user data"),
        k("mark_settings", "reserved: mark toggles (settings, not memory); not written yet"),
    )

    /** The GLASSES app's prefs (a different device: Delete all reaches them through the new epoch in `pc_trace_ctl`, not by this process). */
    val GLASSES_PREFS: List<Entry> = listOf(
        w("mark_queue", "unsent pc_mark outbox; MarkOutbox.onEpoch clears it when the phone's installId or deletion counter changes"),
    )

    /** Files and folders of the GLASSES recorder under its `imu` root (names only; the glasses delete them on a new epoch or a forget request). */
    val GLASSES_FILES: List<Entry> = listOf(
        w("imu", "the glasses recorder's root folder (holds the rest)"),
        w("traces", "closed IMU traces; deleted by an epoch wipe and by a forget range or tombstone"),
        w("imu_trace_tmp", "a trace being written; deleted by an epoch wipe, a forget, and at start-up"),
        k("trace_epoch", "the last deletion epoch seen (install id and counter): the fence itself, no user data; written only after a wipe succeeded"),
        k("trace_epoch.part", "its atomic-write temp file"),
        w("trace_forget", "pending forget requests (ranges and ids); removed once carried out"),
        w("trace_forget.part", "its atomic-write temp file"),
        k("trace_cleanup_pending", "the received-cleanup record (LGC8-min): wipe flag, target epoch and forget ids/ranges, no user data; kept until the cleanup it describes succeeded, replayed at start before recovery"),
        k("trace_cleanup_pending.part", "its atomic-write temp file"),
    )

    /** Folders under `filesDir`. */
    val DIRS: List<Entry> = listOf(
        w("canvas_traces", "sealed IMU traces"), w("canvas_import", "pulled trace imports"), w("canvas_tmp", "tmp files of an unpublished write"),
    )

    /** Folders under the app's EXTERNAL files dir (`getExternalFilesDir(null)`): adb-pushed staging of raw traces; cleared by Delete all and excluded from backup and device transfer. */
    val EXTERNAL_DIRS: List<Entry> = listOf(
        w("trace-staging", "dev-mode trace import staging (raw sensor traces pushed over adb); cleared by Delete all and its resume, excluded from backup and transfer"),
    )

    val wipedExternalDirs get() = EXTERNAL_DIRS.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedTables get() = TABLES.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedPrefs get() = PREFS.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedDirs get() = DIRS.filter { it.fate == Fate.WIPED }.map { it.name }
}
