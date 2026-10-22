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
        w("canvas_run", "photo experiment runs"), w("canvas_run_item", "per-photo run outputs"), w("canvas_score", "per-photo scores"),
        w("canvas_live_trace", "per-photo live outputs"),
        k("canvas_meta", "the monotonic labelRev counter (bumped by the wipe so no earlier result reads as current); the forgetRanges fence value is emptied by the wipe; otherwise no user data"),
        k("canvas_group_state", "publish-group recovery record: gen, hashes, mode, install id and state; needed to recover a deployed plan; no user data"),
        k("canvas_pipeline", "pipelines are user-authored prompts and settings"), k("canvas_pipeline_version", "frozen pipeline versions: prompts"),
        k("canvas_publish", "publish records: hashes and snapshots of prompts, no photo data"),
    )

    /** Phone SharedPreferences names (without `.xml`). */
    val PREFS: List<Entry> = listOf(
        w("canvas_plan_state", "the publish-group and plan state sent to the glasses; cleared so a stale plan is never re-sent"),
        w("canvas_collect", "the Collect-for-tuning session window"),
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
    )

    /** Folders under `filesDir`. */
    val DIRS: List<Entry> = listOf(
        w("canvas_traces", "sealed IMU traces"), w("canvas_import", "pulled trace imports"), w("canvas_tmp", "tmp files of an unpublished write"),
    )

    val wipedTables get() = TABLES.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedPrefs get() = PREFS.filter { it.fate == Fate.WIPED }.map { it.name }
    val wipedDirs get() = DIRS.filter { it.fate == Fate.WIPED }.map { it.name }
}
